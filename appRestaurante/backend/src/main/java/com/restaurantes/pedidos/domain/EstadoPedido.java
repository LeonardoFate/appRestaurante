package com.restaurantes.pedidos.domain;

import java.util.Arrays;

/** Estado de preparacion y entrega de un pedido (el cobro va aparte, en {@link EstadoPago}). El valor de base de datos es {@link #valor()}. */
public enum EstadoPedido {
    CREADO("creado"),
    PAGO_PENDIENTE("pago_pendiente"),
    CONFIRMADO("confirmado"),
    EN_PREPARACION("en_preparacion"),
    LISTO("listo"),
    ASIGNADO("asignado"),
    EN_CAMINO("en_camino"),
    ENTREGADO("entregado"),
    CERRADO("cerrado"),
    CANCELADO("cancelado"),
    DEVUELTO_PARCIAL("devuelto_parcial"),
    DEVUELTO_TOTAL("devuelto_total");

    private final String valor;

    EstadoPedido(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    public static EstadoPedido desde(String valor) {
        return Arrays.stream(values())
                .filter(e -> e.valor.equals(valor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Estado de pedido desconocido: " + valor));
    }

    /** Estados de los que el pedido ya no sale por el flujo normal (la devolucion parcial puede pasar a total). */
    public boolean esFinal() {
        return this == CERRADO || this == CANCELADO || this == DEVUELTO_TOTAL;
    }

    /** El pedido fue cancelado o devuelto: ya no admite cambios de cobro ni de cocina. */
    public boolean estaAnulado() {
        return this == CANCELADO || this == DEVUELTO_PARCIAL || this == DEVUELTO_TOTAL;
    }
}
