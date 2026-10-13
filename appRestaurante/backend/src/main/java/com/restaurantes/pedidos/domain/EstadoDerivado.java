package com.restaurantes.pedidos.domain;

import java.util.List;
import java.util.Optional;

/**
 * Estado que le corresponde a un pedido segun el estado de cocina de sus items. Java puro.
 *
 * <ul>
 *   <li>Todos servidos -> {@code entregado}.</li>
 *   <li>Todos listos o servidos (al menos uno listo) -> {@code listo}.</li>
 *   <li>Alguno preparando, listo o servido -> {@code en_preparacion}.</li>
 *   <li>Todos aun por preparar -> {@code confirmado}.</li>
 * </ul>
 * Los items anulados no cuentan. En delivery la cocina no pasa de {@code listo}: la entrega es del repartidor.
 */
public final class EstadoDerivado {

    private EstadoDerivado() {
    }

    /** @return el estado derivado, o vacio si no queda ningun item activo (todos anulados) */
    public static Optional<EstadoPedido> deLosItems(List<EstadoCocina> items, CanalPedido canal) {
        List<EstadoCocina> activos = items.stream().filter(EstadoCocina::estaActivo).toList();
        if (activos.isEmpty()) {
            return Optional.empty();
        }
        boolean todosServidos = activos.stream().allMatch(e -> e == EstadoCocina.SERVIDO);
        boolean todosListosOServidos = activos.stream().allMatch(e -> e == EstadoCocina.LISTO || e == EstadoCocina.SERVIDO);
        boolean hayAvance = activos.stream().anyMatch(e ->
                e == EstadoCocina.PREPARANDO || e == EstadoCocina.LISTO || e == EstadoCocina.SERVIDO);

        EstadoPedido derivado;
        if (todosServidos) {
            derivado = EstadoPedido.ENTREGADO;
        } else if (todosListosOServidos) {
            derivado = EstadoPedido.LISTO;
        } else if (hayAvance) {
            derivado = EstadoPedido.EN_PREPARACION;
        } else {
            derivado = EstadoPedido.CONFIRMADO;
        }
        if (canal == CanalPedido.DELIVERY_PROPIO && derivado == EstadoPedido.ENTREGADO) {
            derivado = EstadoPedido.LISTO;
        }
        return Optional.of(derivado);
    }

    /** Un pedido entregado y pagado se cierra solo. */
    public static boolean debeCerrarse(EstadoPedido estado, EstadoPago estadoPago) {
        return estado == EstadoPedido.ENTREGADO && estadoPago == EstadoPago.PAGADO;
    }
}
