package com.restaurantes.delivery.internal;

import com.restaurantes.auth.api.Equipo;
import com.restaurantes.delivery.domain.EstadoEntrega;
import com.restaurantes.delivery.internal.EntregaRepository.EntregaDetalle;
import com.restaurantes.delivery.internal.EntregaRepository.EntregaFila;
import com.restaurantes.delivery.internal.EntregaRepository.PendienteDeLiquidar;
import com.restaurantes.pagos.api.PagosApi;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.CanalPedido;
import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.pedidos.domain.EstadoPedido;
import com.restaurantes.shared.audit.Auditoria;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.security.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reparto de los pedidos a domicilio: asignar repartidor, recoger, entregar (cobrando el saldo en efectivo) y liquidar
 * el efectivo con caja. Se ejecuta dentro de la unidad de trabajo del tenant que abre el endpoint.
 *
 * <p>Orden de bloqueo fijo: primero el PEDIDO y despues la ENTREGA (y el cobro, que bloquea el pedido que ya tenemos).
 * El estado del pedido lo mueve {@code pedidos}; aqui solo se mueve el tramo de reparto.
 */
@ApplicationScoped
class EntregasService {

    @Inject
    EntregaRepository repo;

    @Inject
    PedidosApi pedidos;

    @Inject
    PagosApi pagos;

    @Inject
    Equipo equipo;

    @Inject
    Auditoria auditoria;

    @Inject
    CallerContext caller;

    // ------------------------------------------------------------- despacho

    List<EntregaDetalle> listar(List<String> estados) {
        return repo.listar(caller.sucursalId(), estados, null);
    }

    /** Asigna (o reasigna, mientras no se haya recogido) el repartidor de una entrega. */
    EntregaDetalle asignar(UUID pedidoId, UUID repartidorId) {
        PedidoVista pedido = pedidoDeLaSucursal(pedidoId);
        EntregaFila entrega = entregaBloqueada(pedidoId);
        if (!equipo.tieneRolActivo(repartidorId, "repartidor", pedido.sucursalId())) {
            throw ReglaDeNegocioException.invalido("REPARTIDOR_INVALIDO",
                    "Ese usuario no es un repartidor activo de esta sucursal");
        }
        EstadoEntrega.exigir(entrega.estado(), EstadoEntrega.ASIGNADO);
        pedidos.avanzarEntrega(pedidoId, EstadoPedido.ASIGNADO, caller.usuarioId());
        repo.asignar(pedidoId, repartidorId);
        return detalle(pedidoId);
    }

    // ----------------------------------------------------------- repartidor

    List<EntregaDetalle> misEntregas() {
        return repo.listar(caller.sucursalId(), List.of("asignado", "recogido"), caller.usuarioId());
    }

    EntregaDetalle recoger(UUID pedidoId) {
        PedidoVista pedido = pedidoDeLaSucursal(pedidoId);
        EntregaFila entrega = propiaBloqueada(pedidoId);
        if (entrega.estado() == EstadoEntrega.RECOGIDO) {
            return detalle(pedidoId);   // idempotente
        }
        EstadoEntrega.exigir(entrega.estado(), EstadoEntrega.RECOGIDO);
        if ("anticipado".equals(pedido.momentoPago()) && pedido.estadoPago() != EstadoPago.PAGADO) {
            throw ReglaDeNegocioException.conflicto("PAGO_ANTICIPADO_PENDIENTE",
                    "El pedido es de pago anticipado y aun no esta pagado: no puede salir a reparto");
        }
        pedidos.avanzarEntrega(pedidoId, EstadoPedido.EN_CAMINO, caller.usuarioId());
        repo.recoger(pedidoId);
        return detalle(pedidoId);
    }

    /**
     * El repartidor entrega y cobra en efectivo lo que falte: queda registrado como un pago del pedido y como efectivo
     * pendiente de liquidar con caja. Si el pedido ya estaba pagado no se cobra nada.
     */
    EntregaDetalle entregar(UUID pedidoId) {
        pedidoDeLaSucursal(pedidoId);
        EntregaFila entrega = propiaBloqueada(pedidoId);
        if (entrega.estado() == EstadoEntrega.ENTREGADO) {
            return detalle(pedidoId);   // idempotente
        }
        EstadoEntrega.exigir(entrega.estado(), EstadoEntrega.ENTREGADO);
        // Primero el cobro (deja el pedido pagado) y despues el paso a entregado, que lo cierra si ya esta pagado.
        BigDecimal cobrado = pagos.cobrarSaldoEnEfectivo(pedidoId);
        pedidos.avanzarEntrega(pedidoId, EstadoPedido.ENTREGADO, caller.usuarioId());
        repo.entregar(pedidoId, cobrado);
        return detalle(pedidoId);
    }

    /** El repartidor no pudo entregar (ya habia recogido el pedido). El pedido queda para que el despacho decida. */
    EntregaDetalle fallar(UUID pedidoId, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw ReglaDeNegocioException.invalido("MOTIVO_REQUERIDO", "Indique por que no se pudo entregar");
        }
        pedidoDeLaSucursal(pedidoId);
        EntregaFila entrega = propiaBloqueada(pedidoId);
        if (entrega.estado() == EstadoEntrega.FALLIDO) {
            return detalle(pedidoId);   // idempotente
        }
        if (entrega.estado() != EstadoEntrega.RECOGIDO) {
            throw ReglaDeNegocioException.conflicto("ENTREGA_TRANSICION_INVALIDA",
                    "Solo se puede reportar un fallo de una entrega ya recogida");
        }
        repo.fallar(pedidoId, motivo.trim());
        return detalle(pedidoId);
    }

    // ----------------------------------------------------------- liquidacion

    List<PendienteDeLiquidar> pendientesDeLiquidar() {
        return repo.pendientesDeLiquidar(caller.sucursalId());
    }

    /** Caja recibe el efectivo que el repartidor cobro. Idempotente: repetirla no vuelve a contar lo ya liquidado. */
    PendienteDeLiquidar liquidar(UUID repartidorId) {
        PendienteDeLiquidar r = repo.liquidar(repartidorId, caller.sucursalId(), caller.usuarioId());
        if (r.entregas() > 0) {
            auditoria.registrar(caller.usuarioId(), "delivery.liquidar", "usuario", repartidorId, null,
                    Map.of("entregas", r.entregas(), "efectivo", r.efectivo().toPlainString()));
        }
        return r;
    }

    // --------------------------------------------------------------- helpers

    /** El pedido debe existir y ser de la sucursal del token; lo bloquea (primer lugar del orden de bloqueo). */
    private PedidoVista pedidoDeLaSucursal(UUID pedidoId) {
        PedidoVista pedido = pedidos.buscarBloqueando(pedidoId)
                .filter(p -> p.sucursalId().equals(caller.sucursalId()))
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("ENTREGA_NO_ENCONTRADA", "La entrega no existe"));
        if (pedido.canal() != CanalPedido.DELIVERY_PROPIO) {
            throw ReglaDeNegocioException.noEncontrado("ENTREGA_NO_ENCONTRADA", "La entrega no existe");
        }
        return pedido;
    }

    private EntregaFila entregaBloqueada(UUID pedidoId) {
        return repo.porPedido(pedidoId, true).orElseThrow(() -> ReglaDeNegocioException.noEncontrado("ENTREGA_NO_ENCONTRADA",
                "El pedido aun no esta listo para repartir"));
    }

    /** La entrega debe ser del repartidor del token. */
    private EntregaFila propiaBloqueada(UUID pedidoId) {
        EntregaFila entrega = entregaBloqueada(pedidoId);
        if (!caller.usuarioId().equals(entrega.repartidorId())) {
            throw ReglaDeNegocioException.sinAcceso("ENTREGA_AJENA", "Esa entrega no esta asignada a usted");
        }
        return entrega;
    }

    private EntregaDetalle detalle(UUID pedidoId) {
        return repo.listar(caller.sucursalId(), List.of("pendiente", "asignado", "recogido", "entregado", "fallido", "devuelto_a_local"), null)
                .stream().filter(d -> d.pedidoId().equals(pedidoId)).findFirst().orElseThrow();
    }
}
