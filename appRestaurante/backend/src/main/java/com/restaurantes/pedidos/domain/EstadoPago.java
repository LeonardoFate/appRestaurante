package com.restaurantes.pedidos.domain;

import java.util.Arrays;

/** Estado del cobro de un pedido (separado del estado de preparacion; ADR-009). */
public enum EstadoPago {
    SIN_PAGO("sin_pago"),
    PARCIAL("parcial"),
    PAGADO("pagado"),
    REEMBOLSADO_PARCIAL("reembolsado_parcial"),
    REEMBOLSADO("reembolsado");

    private final String valor;

    EstadoPago(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    public static EstadoPago desde(String valor) {
        return Arrays.stream(values())
                .filter(e -> e.valor.equals(valor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Estado de pago desconocido: " + valor));
    }
}
