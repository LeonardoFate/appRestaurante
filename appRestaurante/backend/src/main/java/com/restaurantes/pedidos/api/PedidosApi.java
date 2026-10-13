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

    /**
     * Confirma el pedido (creado -> confirmado) y publica {@code PedidoConfirmado}, que hace que cocina envie los items
     * a sus estaciones. Idempotente: si ya estaba confirmado (o mas adelante) no hace nada.
     *
     * @param usuarioId quien lo confirma (para el historial)
     */
    PedidoVista confirmar(UUID pedidoId, UUID usuarioId);

    /**
     * Cancela el pedido con un motivo y publica {@code PedidoCancelado}. Solo antes de entregar y solo si no hay cobros:
     * con cobros corresponde una devolucion (aun no disponible). Idempotente si ya estaba cancelado.
     */
    PedidoVista cancelar(UUID pedidoId, String motivo, UUID usuarioId);

    /**
     * Lo invoca {@code pagos} tras reembolsar dinero. Si el pedido ya se entrego (o cerro), pasa a {@code devuelto_parcial}
     * o {@code devuelto_total}; antes de entregar solo cambia su estado de cobro y el estado del pedido no se toca.
     * Idempotente y sin retrocesos ({@code devuelto_total} no vuelve a parcial).
     *
     * @param total   {@code true} si tras el reembolso ya no queda nada cobrado
     * @param motivo  obligatorio: queda en el historial del pedido
     * @param usuarioId quien autorizo la devolucion
     */
    void registrarDevolucion(UUID pedidoId, boolean total, String motivo, UUID usuarioId);

    /**
     * Lo invoca {@code cocina} tras cambiar el estado de uno o mas items: recalcula los totales con los items vigentes,
     * cancela el pedido si ya no queda ninguno (y no hay cobros), avanza el estado del pedido segun sus items y lo cierra
     * si queda entregado y pagado. El estado nunca retrocede.
     *
     * @param usuarioId quien origino el cambio; null si lo origino un proceso del sistema (queda como origen "sistema")
     */
    void sincronizarConItems(UUID pedidoId, UUID usuarioId);
}
