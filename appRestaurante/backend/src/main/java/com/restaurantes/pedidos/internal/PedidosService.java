package com.restaurantes.pedidos.internal;

import com.restaurantes.menu.api.CatalogoProductos;
import com.restaurantes.menu.api.ProductoVenta;
import com.restaurantes.pedidos.api.NuevoPedido;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.CalculadoraTotales;
import com.restaurantes.pedidos.domain.CanalPedido;
import com.restaurantes.pedidos.domain.EstadoDerivado;
import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.pedidos.domain.EstadoPedido;
import com.restaurantes.pedidos.domain.LineaPedido;
import com.restaurantes.pedidos.domain.MaquinaDeEstados;
import com.restaurantes.pedidos.domain.Totales;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.events.Outbox;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.security.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Casos de uso de pedidos. NO abren transacciones: se ejecutan dentro de la unidad de trabajo que abrio quien
 * los llama (el endpoint REST o un manejador de eventos), que es lo que fija el tenant. Asi {@code PedidosApi}
 * sirve igual dentro de una peticion HTTP que dentro del relay del outbox.
 *
 * <p>Solo este servicio cambia el estado de un pedido, y siempre siguiendo {@link MaquinaDeEstados}.
 */
@ApplicationScoped
class PedidosService implements PedidosApi {

    @Inject
    PedidoRepository repo;

    @Inject
    CatalogoProductos catalogo;

    @Inject
    CallerContext caller;

    @Inject
    Outbox outbox;

    @ConfigProperty(name = "app.zona-horaria", defaultValue = "America/Guayaquil")
    String zonaHoraria;

    // ------------------------------------------------------------------ crear

    /** Solo se invoca desde una peticion HTTP: usa la sucursal y el usuario del token. */
    @Override
    public PedidoVista crear(NuevoPedido orden) {
        validarOrden(orden);
        UUID pedidoId = orden.id() != null ? orden.id() : UuidV7.next();
        UUID sucursalId = caller.sucursalId();

        // Todo ocurre en la unidad de trabajo del llamante: si algo falla, no queda ni pedido, ni numero
        // consumido, ni historial.
        repo.bloquearCreacion(pedidoId);

        Optional<PedidoVista> existente = repo.buscar(pedidoId, false);
        if (existente.isPresent()) {
            return existente.get().comoRepeticion();
        }

        List<LineaPedido> lineas = construirLineas(sucursalId, orden);
        Totales totales = CalculadoraTotales.calcular(lineas, orden.propina());
        LocalDate fechaOperativa = LocalDate.now(ZoneId.of(zonaHoraria));
        int numero = repo.siguienteNumero(sucursalId, fechaOperativa);

        repo.insertar(pedidoId, sucursalId, numero, orden.canal(), orden.mesaId(), caller.usuarioId(),
                fechaOperativa, totales, lineas);

        PedidoVista vista = repo.buscar(pedidoId, false).orElseThrow();
        return new PedidoVista(vista.id(), vista.sucursalId(), vista.numero(), vista.canal(), vista.estado(),
                vista.estadoPago(), vista.totales(), vista.lineas(), true);
    }

    @Override
    public Optional<PedidoVista> buscar(UUID pedidoId) {
        return repo.buscar(pedidoId, false);
    }

    @Override
    public Optional<PedidoVista> buscarBloqueando(UUID pedidoId) {
        return repo.buscar(pedidoId, true);
    }

    // ------------------------------------------------------------------ cobro

    @Override
    public void actualizarEstadoPago(UUID pedidoId, EstadoPago nuevoEstado) {
        PedidoVista actual = bloquear(pedidoId);
        EstadoPedido estado = EstadoPedido.desde(actual.estado());
        // Un pedido cancelado no se toca; uno devuelto (parcial o total) admite reembolsos pero no NUEVOS cobros.
        boolean esCobro = nuevoEstado == EstadoPago.PAGADO || nuevoEstado == EstadoPago.PARCIAL;
        if (estado == EstadoPedido.CANCELADO || (esCobro && estado.estaAnulado())) {
            throw ReglaDeNegocioException.conflicto("PEDIDO_NO_COBRABLE",
                    "El pedido esta " + actual.estado() + " y no admite cambios de cobro");
        }
        repo.actualizarEstadoPago(pedidoId, nuevoEstado);
        // Un pedido ya entregado que termina de pagarse se cierra solo.
        cerrarSiCorresponde(actual, estado, nuevoEstado, null);
    }

    // ------------------------------------------------------------- devoluciones

    @Override
    public void registrarDevolucion(UUID pedidoId, boolean total, String motivo, UUID usuarioId) {
        if (motivo == null || motivo.isBlank()) {
            throw ReglaDeNegocioException.invalido("MOTIVO_REQUERIDO", "Indique el motivo de la devolucion");
        }
        PedidoVista pedido = bloquear(pedidoId);
        EstadoPedido estado = EstadoPedido.desde(pedido.estado());
        // Antes de entregar no hay "devolucion": solo se devuelve el dinero y el pedido puede cancelarse despues.
        if (estado != EstadoPedido.ENTREGADO && estado != EstadoPedido.CERRADO && estado != EstadoPedido.DEVUELTO_PARCIAL) {
            return;
        }
        EstadoPedido destino = total ? EstadoPedido.DEVUELTO_TOTAL : EstadoPedido.DEVUELTO_PARCIAL;
        if (destino == estado || !MaquinaDeEstados.permitida(pedido.canal(), estado, destino)) {
            return;
        }
        repo.cambiarEstado(pedidoId, estado, destino, usuarioId, motivo.trim(), origenDe(usuarioId));
    }

    // -------------------------------------------------------------- confirmar

    @Override
    public PedidoVista confirmar(UUID pedidoId, UUID usuarioId) {
        PedidoVista pedido = bloquear(pedidoId);
        EstadoPedido estado = EstadoPedido.desde(pedido.estado());
        if (estado == EstadoPedido.CANCELADO) {
            throw ReglaDeNegocioException.conflicto("PEDIDO_CANCELADO", "El pedido esta cancelado");
        }
        if (estado != EstadoPedido.CREADO && estado != EstadoPedido.PAGO_PENDIENTE) {
            return pedido;   // idempotente: ya estaba confirmado o mas adelante
        }
        exigirTransicion(pedido.canal(), estado, EstadoPedido.CONFIRMADO);
        repo.cambiarEstado(pedidoId, estado, EstadoPedido.CONFIRMADO, usuarioId, null, "api");
        outbox.emitir("PedidoConfirmado", "pedido", pedidoId, Map.of(
                "pedidoId", pedidoId.toString(),
                "sucursalId", pedido.sucursalId().toString()));
        return repo.buscar(pedidoId, false).orElseThrow();
    }

    // --------------------------------------------------------------- cancelar

    @Override
    public PedidoVista cancelar(UUID pedidoId, String motivo, UUID usuarioId) {
        if (motivo == null || motivo.isBlank()) {
            throw ReglaDeNegocioException.invalido("MOTIVO_REQUERIDO", "Indique el motivo de la cancelacion");
        }
        PedidoVista pedido = bloquear(pedidoId);
        EstadoPedido estado = EstadoPedido.desde(pedido.estado());
        if (estado == EstadoPedido.CANCELADO) {
            return pedido;   // idempotente
        }
        if (!MaquinaDeEstados.permitida(pedido.canal(), estado, EstadoPedido.CANCELADO)) {
            throw ReglaDeNegocioException.conflicto("TRANSICION_INVALIDA",
                    "Un pedido '" + estado.valor() + "' ya no se puede cancelar; corresponde una devolucion");
        }
        if (!pedido.estadoPago().sinDineroCobrado()) {
            throw ReglaDeNegocioException.conflicto("PEDIDO_CON_COBROS",
                    "El pedido tiene cobros: reembolse primero lo cobrado y despues cancele");
        }
        cancelarInterno(pedido, estado, motivo.trim(), usuarioId);
        return repo.buscar(pedidoId, false).orElseThrow();
    }

    private void cancelarInterno(PedidoVista pedido, EstadoPedido estado, String motivo, UUID usuarioId) {
        repo.cambiarEstado(pedido.id(), estado, EstadoPedido.CANCELADO, usuarioId, motivo, origenDe(usuarioId));
        Map<String, Object> payload = new HashMap<>();
        payload.put("pedidoId", pedido.id().toString());
        payload.put("motivo", motivo);
        payload.put("usuarioId", usuarioId == null ? null : usuarioId.toString());
        outbox.emitir("PedidoCancelado", "pedido", pedido.id(), payload);
    }

    // ----------------------------------------------- sincronizar con la cocina

    @Override
    public void sincronizarConItems(UUID pedidoId, UUID usuarioId) {
        PedidoVista pedido = bloquear(pedidoId);
        EstadoPedido estado = EstadoPedido.desde(pedido.estado());
        if (estado.esFinal() || estado.estaAnulado()) {
            return;
        }

        List<LineaPedido> vigentes = repo.lineasVigentes(pedidoId);
        if (vigentes.isEmpty()) {
            // Se anularon todos los items: el pedido ya no tiene nada que vender.
            if (pedido.estadoPago().sinDineroCobrado()
                    && MaquinaDeEstados.permitida(pedido.canal(), estado, EstadoPedido.CANCELADO)) {
                cancelarInterno(pedido, estado, "Se anularon todos los items del pedido", usuarioId);
            }
            return;
        }

        // Los totales siguen a los items vigentes (anular un item baja el total). La propina se conserva.
        Totales recalculados = CalculadoraTotales.calcular(vigentes, pedido.totales().propina());
        if (!recalculados.equals(pedido.totales())) {
            repo.actualizarTotales(pedidoId, recalculados);
        }

        // Antes de confirmar, la cocina todavia no manda en el estado del pedido.
        if (estado == EstadoPedido.CREADO || estado == EstadoPedido.PAGO_PENDIENTE) {
            return;
        }
        EstadoPedido actual = estado;
        Optional<EstadoPedido> objetivo = EstadoDerivado.deLosItems(repo.estadosDeCocina(pedidoId), pedido.canal());
        if (objetivo.isPresent()) {
            // Solo avanza: si el estado derivado quedara por detras del actual (p. ej. se anulo un item) no se toca.
            for (EstadoPedido paso : MaquinaDeEstados.camino(pedido.canal(), actual, objetivo.get())) {
                repo.cambiarEstado(pedidoId, actual, paso, usuarioId, null, origenDe(usuarioId));
                actual = paso;
                if (paso == EstadoPedido.LISTO) {
                    outbox.emitir("PedidoListo", "pedido", pedidoId, Map.of(
                            "pedidoId", pedidoId.toString(),
                            "sucursalId", pedido.sucursalId().toString()));
                }
            }
        }
        cerrarSiCorresponde(pedido, actual, pedido.estadoPago(), usuarioId);
    }

    // ---------------------------------------------------------------- helpers

    private PedidoVista bloquear(UUID pedidoId) {
        return repo.buscar(pedidoId, true)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe"));
    }

    private void cerrarSiCorresponde(PedidoVista pedido, EstadoPedido estado, EstadoPago estadoPago, UUID usuarioId) {
        if (EstadoDerivado.debeCerrarse(estado, estadoPago)) {
            repo.cambiarEstado(pedido.id(), estado, EstadoPedido.CERRADO, usuarioId, null, origenDe(usuarioId));
        }
    }

    private static void exigirTransicion(CanalPedido canal, EstadoPedido desde, EstadoPedido hasta) {
        if (!MaquinaDeEstados.permitida(canal, desde, hasta)) {
            throw ReglaDeNegocioException.conflicto("TRANSICION_INVALIDA",
                    "No se puede pasar de '" + desde.valor() + "' a '" + hasta.valor() + "'");
        }
    }

    private static String origenDe(UUID usuarioId) {
        return usuarioId == null ? "sistema" : "api";
    }

    private static void validarOrden(NuevoPedido orden) {
        if (orden == null || orden.canal() == null) {
            throw ReglaDeNegocioException.invalido("CANAL_REQUERIDO", "El canal del pedido es obligatorio");
        }
        if (orden.canal() == CanalPedido.DELIVERY_PROPIO) {
            throw ReglaDeNegocioException.invalido("CANAL_NO_SOPORTADO", "El canal delivery aun no esta disponible");
        }
        if (orden.canal() == CanalPedido.SALON && orden.mesaId() == null) {
            throw ReglaDeNegocioException.invalido("MESA_REQUERIDA", "Un pedido de salon requiere mesa");
        }
        if (orden.lineas() == null || orden.lineas().isEmpty()) {
            throw ReglaDeNegocioException.invalido("PEDIDO_SIN_ITEMS", "El pedido debe tener al menos un item");
        }
    }

    private List<LineaPedido> construirLineas(UUID sucursalId, NuevoPedido orden) {
        List<UUID> ids = orden.lineas().stream().map(NuevoPedido.NuevaLinea::productoId).distinct().toList();
        Map<UUID, ProductoVenta> productos = catalogo.buscar(sucursalId, ids);

        List<LineaPedido> lineas = new ArrayList<>();
        for (NuevoPedido.NuevaLinea nueva : orden.lineas()) {
            ProductoVenta p = productos.get(nueva.productoId());
            if (p == null) {
                throw ReglaDeNegocioException.invalido("PRODUCTO_NO_ENCONTRADO", "Un producto del pedido no existe");
            }
            if (!p.activo() || !p.disponible()) {
                throw ReglaDeNegocioException.conflicto("PRODUCTO_NO_DISPONIBLE", "'" + p.nombre() + "' no esta disponible");
            }
            lineas.add(new LineaPedido(
                    nueva.id() != null ? nueva.id() : UuidV7.next(),
                    p.id(), p.nombre(), p.precioUnitario(), p.porcentajeIva(), p.codigoIvaSri(),
                    nueva.cantidad(), nueva.nota(), p.estacionId()));
        }
        return lineas;
    }
}
