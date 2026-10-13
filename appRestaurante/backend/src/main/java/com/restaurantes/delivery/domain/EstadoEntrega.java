package com.restaurantes.delivery.domain;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estado de la entrega de un pedido a domicilio (tabla {@code entregas}). Java puro: la tabla de transiciones se prueba
 * par por par. El estado del PEDIDO lo mueve {@code pedidos}; esto es solo el tramo de reparto.
 *
 * <p>{@code fallido} agrupa dos cosas: el repartidor no pudo entregar, o el pedido se cancelo con la entrega en curso.
 * Desde ahi solo se podria devolver el pedido al local ({@code devuelto_a_local}), que aun no tiene caso de uso.
 */
public enum EstadoEntrega {
    PENDIENTE("pendiente"),
    ASIGNADO("asignado"),
    RECOGIDO("recogido"),
    ENTREGADO("entregado"),
    FALLIDO("fallido"),
    DEVUELTO_A_LOCAL("devuelto_a_local");

    private static final Map<EstadoEntrega, Set<EstadoEntrega>> PERMITIDAS = new EnumMap<>(EstadoEntrega.class);

    static {
        PERMITIDAS.put(PENDIENTE, EnumSet.of(ASIGNADO, FALLIDO));
        PERMITIDAS.put(ASIGNADO, EnumSet.of(ASIGNADO, RECOGIDO, FALLIDO));   // asignado -> asignado = reasignar
        PERMITIDAS.put(RECOGIDO, EnumSet.of(ENTREGADO, FALLIDO));
        PERMITIDAS.put(ENTREGADO, EnumSet.noneOf(EstadoEntrega.class));
        PERMITIDAS.put(FALLIDO, EnumSet.of(DEVUELTO_A_LOCAL));
        PERMITIDAS.put(DEVUELTO_A_LOCAL, EnumSet.noneOf(EstadoEntrega.class));
    }

    private final String valor;

    EstadoEntrega(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    public static EstadoEntrega desde(String texto) {
        for (EstadoEntrega e : values()) {
            if (e.valor.equals(texto)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Estado de entrega desconocido: " + texto);
    }

    public static boolean permitida(EstadoEntrega desde, EstadoEntrega hasta) {
        return PERMITIDAS.get(desde).contains(hasta);
    }

    public static void exigir(EstadoEntrega desde, EstadoEntrega hasta) {
        if (!permitida(desde, hasta)) {
            throw ReglaDeNegocioException.conflicto("ENTREGA_TRANSICION_INVALIDA",
                    "La entrega esta '" + desde.valor + "' y no puede pasar a '" + hasta.valor + "'");
        }
    }

    /** Fin del recorrido: ya no admite cambios de reparto. */
    public boolean esFinal() {
        return PERMITIDAS.get(this).isEmpty();
    }
}
