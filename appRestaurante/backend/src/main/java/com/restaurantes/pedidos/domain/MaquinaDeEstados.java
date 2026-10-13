package com.restaurantes.pedidos.domain;

import static com.restaurantes.pedidos.domain.EstadoPedido.ASIGNADO;
import static com.restaurantes.pedidos.domain.EstadoPedido.CANCELADO;
import static com.restaurantes.pedidos.domain.EstadoPedido.CERRADO;
import static com.restaurantes.pedidos.domain.EstadoPedido.CONFIRMADO;
import static com.restaurantes.pedidos.domain.EstadoPedido.CREADO;
import static com.restaurantes.pedidos.domain.EstadoPedido.DEVUELTO_PARCIAL;
import static com.restaurantes.pedidos.domain.EstadoPedido.DEVUELTO_TOTAL;
import static com.restaurantes.pedidos.domain.EstadoPedido.EN_CAMINO;
import static com.restaurantes.pedidos.domain.EstadoPedido.EN_PREPARACION;
import static com.restaurantes.pedidos.domain.EstadoPedido.ENTREGADO;
import static com.restaurantes.pedidos.domain.EstadoPedido.LISTO;
import static com.restaurantes.pedidos.domain.EstadoPedido.PAGO_PENDIENTE;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transiciones validas del estado de un pedido, por canal (ADR-009). Java puro: es una tabla, no logica dispersa.
 *
 * <ul>
 *   <li><b>Salon y para llevar:</b> creado -> confirmado -> en_preparacion -> listo -> entregado -> cerrado.</li>
 *   <li><b>Delivery:</b> ademas pasa por pago_pendiente (opcional, si se paga antes), asignado y en_camino.</li>
 *   <li><b>Cancelacion:</b> solo ANTES de entregar. Despues de entregar, lo que corresponde es una devolucion
 *       (con nota de credito si ya hay factura autorizada).</li>
 *   <li>El cobro NO es un estado: vive en {@link EstadoPago}.</li>
 * </ul>
 *
 * <p>Un pedido avanza de a un paso; si hace falta llegar mas lejos se recorre el camino y cada paso queda en el
 * historial ({@link #camino}).
 */
public final class MaquinaDeEstados {

    private static final Set<EstadoPedido> LATERALES = EnumSet.of(CANCELADO, DEVUELTO_PARCIAL, DEVUELTO_TOTAL);

    private static final Map<EstadoPedido, Set<EstadoPedido>> LOCAL = new EnumMap<>(EstadoPedido.class);
    private static final Map<EstadoPedido, Set<EstadoPedido>> DELIVERY = new EnumMap<>(EstadoPedido.class);

    static {
        LOCAL.put(CREADO, EnumSet.of(CONFIRMADO, CANCELADO));
        LOCAL.put(CONFIRMADO, EnumSet.of(EN_PREPARACION, CANCELADO));
        LOCAL.put(EN_PREPARACION, EnumSet.of(LISTO, CANCELADO));
        LOCAL.put(LISTO, EnumSet.of(ENTREGADO, CANCELADO));
        LOCAL.put(ENTREGADO, EnumSet.of(CERRADO, DEVUELTO_PARCIAL, DEVUELTO_TOTAL));
        LOCAL.put(CERRADO, EnumSet.of(DEVUELTO_PARCIAL, DEVUELTO_TOTAL));
        LOCAL.put(DEVUELTO_PARCIAL, EnumSet.of(DEVUELTO_TOTAL));

        DELIVERY.put(CREADO, EnumSet.of(PAGO_PENDIENTE, CONFIRMADO, CANCELADO));
        DELIVERY.put(PAGO_PENDIENTE, EnumSet.of(CONFIRMADO, CANCELADO));
        DELIVERY.put(CONFIRMADO, EnumSet.of(EN_PREPARACION, CANCELADO));
        DELIVERY.put(EN_PREPARACION, EnumSet.of(LISTO, CANCELADO));
        DELIVERY.put(LISTO, EnumSet.of(ASIGNADO, CANCELADO));
        DELIVERY.put(ASIGNADO, EnumSet.of(EN_CAMINO, CANCELADO));
        DELIVERY.put(EN_CAMINO, EnumSet.of(ENTREGADO, CANCELADO));
        DELIVERY.put(ENTREGADO, EnumSet.of(CERRADO, DEVUELTO_PARCIAL, DEVUELTO_TOTAL));
        DELIVERY.put(CERRADO, EnumSet.of(DEVUELTO_PARCIAL, DEVUELTO_TOTAL));
        DELIVERY.put(DEVUELTO_PARCIAL, EnumSet.of(DEVUELTO_TOTAL));
    }

    private MaquinaDeEstados() {
    }

    private static Map<EstadoPedido, Set<EstadoPedido>> tablaDe(CanalPedido canal) {
        return canal == CanalPedido.DELIVERY_PROPIO ? DELIVERY : LOCAL;
    }

    /** Estados a los que se puede pasar directamente desde {@code desde}. */
    public static Set<EstadoPedido> siguientes(CanalPedido canal, EstadoPedido desde) {
        Set<EstadoPedido> destinos = tablaDe(canal).get(desde);
        return destinos == null ? Set.of() : Collections.unmodifiableSet(destinos);
    }

    public static boolean permitida(CanalPedido canal, EstadoPedido desde, EstadoPedido hasta) {
        return siguientes(canal, desde).contains(hasta);
    }

    /**
     * Pasos intermedios (incluido el destino) para llegar de {@code desde} a {@code hasta} avanzando por el flujo
     * normal, o lista vacia si ya esta alli o no hay camino (por ejemplo, hacia atras). Los estados laterales
     * (cancelado, devuelto) solo se alcanzan en un paso directo.
     */
    public static List<EstadoPedido> camino(CanalPedido canal, EstadoPedido desde, EstadoPedido hasta) {
        if (desde == hasta) {
            return List.of();
        }
        if (LATERALES.contains(hasta)) {
            return permitida(canal, desde, hasta) ? List.of(hasta) : List.of();
        }
        Map<EstadoPedido, EstadoPedido> origen = new HashMap<>();
        Deque<EstadoPedido> pendientes = new ArrayDeque<>();
        pendientes.add(desde);
        origen.put(desde, null);
        while (!pendientes.isEmpty()) {
            EstadoPedido actual = pendientes.poll();
            for (EstadoPedido siguiente : siguientes(canal, actual)) {
                if (LATERALES.contains(siguiente) || origen.containsKey(siguiente)) {
                    continue;
                }
                origen.put(siguiente, actual);
                if (siguiente == hasta) {
                    List<EstadoPedido> pasos = new ArrayList<>();
                    for (EstadoPedido e = hasta; e != desde; e = origen.get(e)) {
                        pasos.add(0, e);
                    }
                    return pasos;
                }
                pendientes.add(siguiente);
            }
        }
        return List.of();
    }
}
