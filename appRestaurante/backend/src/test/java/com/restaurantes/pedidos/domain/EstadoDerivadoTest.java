package com.restaurantes.pedidos.domain;

import static com.restaurantes.pedidos.domain.EstadoCocina.ANULADO;
import static com.restaurantes.pedidos.domain.EstadoCocina.ENVIADO;
import static com.restaurantes.pedidos.domain.EstadoCocina.LISTO;
import static com.restaurantes.pedidos.domain.EstadoCocina.PENDIENTE;
import static com.restaurantes.pedidos.domain.EstadoCocina.PREPARANDO;
import static com.restaurantes.pedidos.domain.EstadoCocina.SERVIDO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EstadoDerivadoTest {

    private static Optional<EstadoPedido> salon(EstadoCocina... items) {
        return EstadoDerivado.deLosItems(List.of(items), CanalPedido.SALON);
    }

    @Test
    void conTodosLosItemsPorPrepararElPedidoEstaConfirmado() {
        assertEquals(Optional.of(EstadoPedido.CONFIRMADO), salon(ENVIADO));
        assertEquals(Optional.of(EstadoPedido.CONFIRMADO), salon(ENVIADO, ENVIADO));
        assertEquals(Optional.of(EstadoPedido.CONFIRMADO), salon(PENDIENTE, ENVIADO));
    }

    @Test
    void conAlgunItemEnMarchaElPedidoEstaEnPreparacion() {
        assertEquals(Optional.of(EstadoPedido.EN_PREPARACION), salon(PREPARANDO));
        assertEquals(Optional.of(EstadoPedido.EN_PREPARACION), salon(PREPARANDO, ENVIADO));
        assertEquals(Optional.of(EstadoPedido.EN_PREPARACION), salon(LISTO, ENVIADO), "uno listo y otro sin empezar");
        assertEquals(Optional.of(EstadoPedido.EN_PREPARACION), salon(SERVIDO, PREPARANDO), "entrada servida y principal en la parrilla");
    }

    @Test
    void conTodosListosOServidosElPedidoEstaListo() {
        assertEquals(Optional.of(EstadoPedido.LISTO), salon(LISTO));
        assertEquals(Optional.of(EstadoPedido.LISTO), salon(LISTO, LISTO));
        assertEquals(Optional.of(EstadoPedido.LISTO), salon(LISTO, SERVIDO), "algo ya servido y lo demas listo para servir");
    }

    @Test
    void conTodosServidosElPedidoEstaEntregado() {
        assertEquals(Optional.of(EstadoPedido.ENTREGADO), salon(SERVIDO));
        assertEquals(Optional.of(EstadoPedido.ENTREGADO), salon(SERVIDO, SERVIDO));
    }

    @Test
    void losItemsAnuladosNoCuentan() {
        assertEquals(Optional.of(EstadoPedido.ENTREGADO), salon(SERVIDO, ANULADO));
        assertEquals(Optional.of(EstadoPedido.LISTO), salon(LISTO, ANULADO));
        assertEquals(Optional.of(EstadoPedido.CONFIRMADO), salon(ENVIADO, ANULADO));
    }

    @Test
    void sinItemsActivosNoHayEstado() {
        assertTrue(salon(ANULADO).isEmpty());
        assertTrue(salon(ANULADO, ANULADO).isEmpty());
        assertTrue(EstadoDerivado.deLosItems(List.of(), CanalPedido.SALON).isEmpty());
    }

    @Test
    void enDeliveryLaCocinaNoPasaDeListo() {
        assertEquals(Optional.of(EstadoPedido.LISTO), EstadoDerivado.deLosItems(List.of(SERVIDO), CanalPedido.DELIVERY_PROPIO),
                "la entrega al cliente es del repartidor, no de la cocina");
        assertEquals(Optional.of(EstadoPedido.LISTO), EstadoDerivado.deLosItems(List.of(LISTO), CanalPedido.DELIVERY_PROPIO));
    }

    @Test
    void elResultadoNoDependeDelOrdenDeLosItems() {
        assertEquals(salon(ENVIADO, PREPARANDO, LISTO), salon(LISTO, ENVIADO, PREPARANDO));
        assertEquals(salon(SERVIDO, LISTO), salon(LISTO, SERVIDO));
    }

    @Test
    void unPedidoEntregadoYPagadoSeCierraSolo() {
        assertTrue(EstadoDerivado.debeCerrarse(EstadoPedido.ENTREGADO, EstadoPago.PAGADO));
        assertFalse(EstadoDerivado.debeCerrarse(EstadoPedido.ENTREGADO, EstadoPago.PARCIAL));
        assertFalse(EstadoDerivado.debeCerrarse(EstadoPedido.ENTREGADO, EstadoPago.SIN_PAGO));
        assertFalse(EstadoDerivado.debeCerrarse(EstadoPedido.LISTO, EstadoPago.PAGADO), "pagado pero aun no entregado");
        assertFalse(EstadoDerivado.debeCerrarse(EstadoPedido.CERRADO, EstadoPago.PAGADO));
    }
}
