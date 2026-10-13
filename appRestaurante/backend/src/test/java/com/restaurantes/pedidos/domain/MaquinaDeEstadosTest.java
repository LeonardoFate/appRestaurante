package com.restaurantes.pedidos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Prueba la tabla de transiciones PAR POR PAR: las {@code 12 x 12} combinaciones de cada canal se comparan con una
 * lista esperada escrita aparte (no se reutiliza la logica que se prueba).
 */
class MaquinaDeEstadosTest {

    /** Transiciones validas de salon y para llevar, escritas "desde>hasta". */
    private static final Set<String> LOCAL = Set.of(
            "creado>confirmado", "creado>cancelado",
            "confirmado>en_preparacion", "confirmado>cancelado",
            "en_preparacion>listo", "en_preparacion>cancelado",
            "listo>entregado", "listo>cancelado",
            "entregado>cerrado", "entregado>devuelto_parcial", "entregado>devuelto_total",
            "cerrado>devuelto_parcial", "cerrado>devuelto_total",
            "devuelto_parcial>devuelto_total");

    /** Transiciones validas del delivery. */
    private static final Set<String> DELIVERY = Set.of(
            "creado>pago_pendiente", "creado>confirmado", "creado>cancelado",
            "pago_pendiente>confirmado", "pago_pendiente>cancelado",
            "confirmado>en_preparacion", "confirmado>cancelado",
            "en_preparacion>listo", "en_preparacion>cancelado",
            "listo>asignado", "listo>cancelado",
            "asignado>en_camino", "asignado>cancelado",
            "en_camino>entregado", "en_camino>cancelado",
            "entregado>cerrado", "entregado>devuelto_parcial", "entregado>devuelto_total",
            "cerrado>devuelto_parcial", "cerrado>devuelto_total",
            "devuelto_parcial>devuelto_total");

    private static void compararTabla(CanalPedido canal, Set<String> esperadas) {
        int validas = 0;
        for (EstadoPedido desde : EstadoPedido.values()) {
            for (EstadoPedido hasta : EstadoPedido.values()) {
                boolean esperado = esperadas.contains(desde.valor() + ">" + hasta.valor());
                assertEquals(esperado, MaquinaDeEstados.permitida(canal, desde, hasta),
                        canal + ": " + desde.valor() + " -> " + hasta.valor());
                if (esperado) {
                    validas++;
                }
            }
        }
        assertEquals(esperadas.size(), validas, "toda transicion esperada existe en el enum");
    }

    @Test
    void salonSigueLaTablaParPorPar() {
        compararTabla(CanalPedido.SALON, LOCAL);
    }

    @Test
    void paraLlevarSigueLaTablaParPorPar() {
        compararTabla(CanalPedido.PARA_LLEVAR, LOCAL);
    }

    @Test
    void deliverySigueLaTablaParPorPar() {
        compararTabla(CanalPedido.DELIVERY_PROPIO, DELIVERY);
    }

    @Test
    void nadieSaleDeCanceladoNiDeDevueltoTotal() {
        for (CanalPedido canal : CanalPedido.values()) {
            assertTrue(MaquinaDeEstados.siguientes(canal, EstadoPedido.CANCELADO).isEmpty());
            assertTrue(MaquinaDeEstados.siguientes(canal, EstadoPedido.DEVUELTO_TOTAL).isEmpty());
        }
    }

    @Test
    void soloSePuedeCancelarAntesDeEntregar() {
        for (CanalPedido canal : CanalPedido.values()) {
            for (EstadoPedido estado : List.of(EstadoPedido.ENTREGADO, EstadoPedido.CERRADO, EstadoPedido.CANCELADO,
                    EstadoPedido.DEVUELTO_PARCIAL, EstadoPedido.DEVUELTO_TOTAL)) {
                assertFalse(MaquinaDeEstados.permitida(canal, estado, EstadoPedido.CANCELADO),
                        "no se cancela desde " + estado.valor() + ": corresponde una devolucion");
            }
            assertTrue(MaquinaDeEstados.permitida(canal, EstadoPedido.LISTO, EstadoPedido.CANCELADO));
        }
    }

    @Test
    void lasDevolucionesSoloOcurrenDespuesDeEntregar() {
        for (CanalPedido canal : CanalPedido.values()) {
            for (EstadoPedido estado : EstadoPedido.values()) {
                boolean despuesDeEntregar = estado == EstadoPedido.ENTREGADO || estado == EstadoPedido.CERRADO
                        || estado == EstadoPedido.DEVUELTO_PARCIAL;
                assertEquals(despuesDeEntregar, MaquinaDeEstados.permitida(canal, estado, EstadoPedido.DEVUELTO_TOTAL),
                        canal + ": " + estado.valor() + " -> devuelto_total");
            }
        }
    }

    @Test
    void nuncaSeRetrocede() {
        List<EstadoPedido> cadena = List.of(EstadoPedido.CREADO, EstadoPedido.CONFIRMADO, EstadoPedido.EN_PREPARACION,
                EstadoPedido.LISTO, EstadoPedido.ENTREGADO, EstadoPedido.CERRADO);
        for (int i = 0; i < cadena.size(); i++) {
            for (int j = 0; j <= i; j++) {
                assertFalse(MaquinaDeEstados.permitida(CanalPedido.SALON, cadena.get(i), cadena.get(j)),
                        cadena.get(i).valor() + " -> " + cadena.get(j).valor());
            }
        }
    }

    // ---------------------------------------------------------------- camino

    @Test
    void elCaminoRecorreLosEstadosIntermedios() {
        assertEquals(List.of(EstadoPedido.CONFIRMADO, EstadoPedido.EN_PREPARACION, EstadoPedido.LISTO),
                MaquinaDeEstados.camino(CanalPedido.SALON, EstadoPedido.CREADO, EstadoPedido.LISTO));
        assertEquals(List.of(EstadoPedido.EN_PREPARACION),
                MaquinaDeEstados.camino(CanalPedido.SALON, EstadoPedido.CONFIRMADO, EstadoPedido.EN_PREPARACION));
        assertEquals(List.of(EstadoPedido.LISTO, EstadoPedido.ENTREGADO, EstadoPedido.CERRADO),
                MaquinaDeEstados.camino(CanalPedido.PARA_LLEVAR, EstadoPedido.EN_PREPARACION, EstadoPedido.CERRADO));
    }

    @Test
    void elCaminoDeDeliveryPasaPorAsignadoYEnCamino() {
        assertEquals(List.of(EstadoPedido.ASIGNADO, EstadoPedido.EN_CAMINO, EstadoPedido.ENTREGADO),
                MaquinaDeEstados.camino(CanalPedido.DELIVERY_PROPIO, EstadoPedido.LISTO, EstadoPedido.ENTREGADO));
    }

    @Test
    void sinCaminoHaciaAtrasNiAlMismoEstado() {
        assertTrue(MaquinaDeEstados.camino(CanalPedido.SALON, EstadoPedido.LISTO, EstadoPedido.CONFIRMADO).isEmpty());
        assertTrue(MaquinaDeEstados.camino(CanalPedido.SALON, EstadoPedido.LISTO, EstadoPedido.LISTO).isEmpty());
        assertTrue(MaquinaDeEstados.camino(CanalPedido.SALON, EstadoPedido.CANCELADO, EstadoPedido.LISTO).isEmpty());
        assertTrue(MaquinaDeEstados.camino(CanalPedido.SALON, EstadoPedido.CERRADO, EstadoPedido.ENTREGADO).isEmpty());
    }

    @Test
    void losEstadosLateralesSoloSeAlcanzanEnUnPasoDirecto() {
        assertEquals(List.of(EstadoPedido.CANCELADO),
                MaquinaDeEstados.camino(CanalPedido.SALON, EstadoPedido.EN_PREPARACION, EstadoPedido.CANCELADO));
        assertTrue(MaquinaDeEstados.camino(CanalPedido.SALON, EstadoPedido.ENTREGADO, EstadoPedido.CANCELADO).isEmpty());
        assertEquals(List.of(EstadoPedido.DEVUELTO_TOTAL),
                MaquinaDeEstados.camino(CanalPedido.SALON, EstadoPedido.CERRADO, EstadoPedido.DEVUELTO_TOTAL));
    }

    @Test
    void todoCaminoEstaFormadoPorPasosPermitidosYTerminaEnElDestino() {
        for (CanalPedido canal : CanalPedido.values()) {
            for (EstadoPedido desde : EstadoPedido.values()) {
                for (EstadoPedido hasta : EstadoPedido.values()) {
                    List<EstadoPedido> camino = MaquinaDeEstados.camino(canal, desde, hasta);
                    EstadoPedido anterior = desde;
                    for (EstadoPedido paso : camino) {
                        assertTrue(MaquinaDeEstados.permitida(canal, anterior, paso),
                                canal + ": paso invalido " + anterior.valor() + " -> " + paso.valor());
                        anterior = paso;
                    }
                    if (!camino.isEmpty()) {
                        assertEquals(hasta, camino.get(camino.size() - 1));
                    }
                }
            }
        }
    }
}
