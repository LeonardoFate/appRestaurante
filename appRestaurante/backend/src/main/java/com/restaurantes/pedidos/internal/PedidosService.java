package com.restaurantes.pedidos.internal;

import com.restaurantes.menu.api.CatalogoProductos;
import com.restaurantes.menu.api.ProductoVenta;
import com.restaurantes.pedidos.api.NuevoPedido;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.CalculadoraTotales;
import com.restaurantes.pedidos.domain.CanalPedido;
import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.pedidos.domain.LineaPedido;
import com.restaurantes.pedidos.domain.Totales;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.security.CallerContext;
import com.restaurantes.shared.tenancy.TenantTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class PedidosService implements PedidosApi {

    @Inject
    TenantTransaction tx;

    @Inject
    PedidoRepository repo;

    @Inject
    CatalogoProductos catalogo;

    @Inject
    CallerContext caller;

    @ConfigProperty(name = "app.zona-horaria", defaultValue = "America/Guayaquil")
    String zonaHoraria;

    @Override
    public PedidoVista crear(NuevoPedido orden) {
        validarOrden(orden);
        UUID pedidoId = orden.id() != null ? orden.id() : UuidV7.next();
        UUID sucursalId = caller.sucursalId();

        // Es una unica transaccion: si algo falla, no queda ni pedido, ni numero consumido, ni historial.
        return tx.withTenant(caller.tenantId(), c -> {
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
            return new PedidoVista(vista.id(), vista.numero(), vista.canal(), vista.estado(), vista.estadoPago(),
                    vista.totales(), vista.lineas(), true);
        });
    }

    @Override
    public Optional<PedidoVista> buscar(UUID pedidoId) {
        return tx.withTenant(caller.tenantId(), c -> repo.buscar(pedidoId, false));
    }

    @Override
    public Optional<PedidoVista> buscarBloqueando(UUID pedidoId) {
        return tx.withTenant(caller.tenantId(), c -> repo.buscar(pedidoId, true));
    }

    @Override
    public void actualizarEstadoPago(UUID pedidoId, EstadoPago nuevoEstado) {
        tx.runInTenant(caller.tenantId(), c -> {
            PedidoVista actual = repo.buscar(pedidoId, true)
                    .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe"));
            if (esTerminal(actual.estado())) {
                throw ReglaDeNegocioException.conflicto("PEDIDO_NO_COBRABLE",
                        "El pedido esta " + actual.estado() + " y no admite cambios de cobro");
            }
            repo.actualizarEstadoPago(pedidoId, nuevoEstado);
        });
    }

    private static boolean esTerminal(String estado) {
        return switch (estado) {
            case "cancelado", "devuelto_total", "devuelto_parcial" -> true;
            default -> false;
        };
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
