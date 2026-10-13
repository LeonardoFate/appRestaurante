package com.restaurantes.pedidos.domain;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estado de preparacion de UN item (el estado de cocina va por item, no por pedido: permite enviar las entradas
 * antes que los platos fuertes). El valor de base de datos es {@link #valor()}.
 *
 * <p>Flujo: pendiente -> enviado -> preparando -> listo -> servido. Un item enviado puede marcarse listo sin pasar por
 * "preparando" (platos simples). Se puede anular hasta antes de servirse.
 */
public enum EstadoCocina {
    PENDIENTE("pendiente"),
    ENVIADO("enviado"),
    PREPARANDO("preparando"),
    LISTO("listo"),
    SERVIDO("servido"),
    ANULADO("anulado");

    private static final Map<EstadoCocina, Set<EstadoCocina>> TRANSICIONES = new EnumMap<>(EstadoCocina.class);

    static {
        TRANSICIONES.put(PENDIENTE, EnumSet.of(ENVIADO, ANULADO));
        TRANSICIONES.put(ENVIADO, EnumSet.of(PREPARANDO, LISTO, ANULADO));
        TRANSICIONES.put(PREPARANDO, EnumSet.of(LISTO, ANULADO));
        TRANSICIONES.put(LISTO, EnumSet.of(SERVIDO, ANULADO));
        TRANSICIONES.put(SERVIDO, EnumSet.noneOf(EstadoCocina.class));
        TRANSICIONES.put(ANULADO, EnumSet.noneOf(EstadoCocina.class));
    }

    private final String valor;

    EstadoCocina(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    public static EstadoCocina desde(String valor) {
        return Arrays.stream(values())
                .filter(e -> e.valor.equals(valor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Estado de cocina desconocido: " + valor));
    }

    public boolean puedePasarA(EstadoCocina destino) {
        return TRANSICIONES.get(this).contains(destino);
    }

    /** El item sigue contando para el pedido (no fue anulado). */
    public boolean estaActivo() {
        return this != ANULADO;
    }
}
