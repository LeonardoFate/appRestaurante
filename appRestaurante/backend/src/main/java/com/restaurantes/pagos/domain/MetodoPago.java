package com.restaurantes.pagos.domain;

import java.util.Arrays;

/** Metodo de cobro. El valor de base de datos es {@link #valor()}. */
public enum MetodoPago {
    EFECTIVO("efectivo", true),
    TARJETA_DATAFONO("tarjeta_datafono", true),
    TRANSFERENCIA("transferencia", true),
    /** Pasarela de pago: requiere integracion con el proveedor (aun no disponible). */
    PASARELA("pasarela", false),
    OTRO("otro", true);

    private final String valor;
    private final boolean soportado;

    MetodoPago(String valor, boolean soportado) {
        this.valor = valor;
        this.soportado = soportado;
    }

    public String valor() {
        return valor;
    }

    /** {@code true} si el metodo se puede registrar hoy sin integracion externa. */
    public boolean soportado() {
        return soportado;
    }

    public static MetodoPago desde(String valor) {
        return Arrays.stream(values())
                .filter(m -> m.valor.equals(valor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Metodo de pago desconocido: " + valor));
    }
}
