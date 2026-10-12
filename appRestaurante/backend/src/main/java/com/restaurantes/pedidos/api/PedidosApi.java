package com.restaurantes.pedidos.api;

import com.restaurantes.pedidos.domain.EstadoPago;
import java.util.Optional;
import java.util.UUID;

/**
 * Lo unico del modulo {@code pedidos} que otros modulos pueden usar. Solo este modulo cambia el estado
 * de un pedido. Debe llamarse dentro de una unidad de trabajo de tenant.
 */
public interface PedidosApi {

    /** Crea el pedido. Idempotente por {@code id}: repetir la orden devuelve el mismo pedido. */
    PedidoVista crear(NuevoPedido orden);

    /** El pedido del tenant actual, o vacio si no existe o pertenece a otro tenant. */
    Optional<PedidoVista> buscar(UUID pedidoId);

    /**
     * Igual que {@link #buscar} pero bloquea la fila del pedido hasta que termine la transaccion. Lo usa quien va a
     * decidir algo a partir de lo leido (por ejemplo, cobrar): serializa las operaciones concurrentes sobre el
     * mismo pedido y garantiza que lo leido despues del bloqueo es lo ultimo confirmado.
     */
    Optional<PedidoVista> buscarBloqueando(UUID pedidoId);

    /** Lo invoca {@code pagos} cuando cambia lo cobrado. {@code pedidos} decide si la transicion es valida. */
    void actualizarEstadoPago(UUID pedidoId, EstadoPago nuevoEstado);
}
