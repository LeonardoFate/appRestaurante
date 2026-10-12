package com.restaurantes.pedidos.domain;

import java.util.Arrays;

/** Canal por el que entra un pedido. El valor de base de datos es {@link #valor()}. */
public enum CanalPedido {
    SALON("salon"),
    PARA_LLEVAR("para_llevar"),
    DELIVERY_PROPIO("delivery_propio");

    private final String valor;

    CanalPedido(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    public static CanalPedido desde(String valor) {
        return Arrays.stream(values())
                .filter(c -> c.valor.equals(valor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Canal desconocido: " + valor));
    }
}
