package com.restaurantes.delivery.domain;

import com.restaurantes.shared.errors.ReglaDeNegocioException;

/** Cuando paga el cliente de un pedido a domicilio. Los valores son los del esquema ({@code pedidos.momento_pago}). */
public enum MomentoDePago {
    ANTICIPADO("anticipado"),
    CONTRA_ENTREGA("contra_entrega");

    private final String valor;

    MomentoDePago(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    /** Sin valor, el delivery se cobra contra entrega (lo habitual). {@code al_final} no tiene sentido sin mesa. */
    public static MomentoDePago desde(String texto) {
        if (texto == null || texto.isBlank()) {
            return CONTRA_ENTREGA;
        }
        for (MomentoDePago m : values()) {
            if (m.valor.equals(texto)) {
                return m;
            }
        }
        throw ReglaDeNegocioException.invalido("MOMENTO_PAGO_INVALIDO", "Momento de pago desconocido: " + texto);
    }
}
