package com.restaurantes.delivery.domain;

import static com.restaurantes.delivery.domain.EstadoEntrega.ASIGNADO;
import static com.restaurantes.delivery.domain.EstadoEntrega.DEVUELTO_A_LOCAL;
import static com.restaurantes.delivery.domain.EstadoEntrega.ENTREGADO;
import static com.restaurantes.delivery.domain.EstadoEntrega.FALLIDO;
import static com.restaurantes.delivery.domain.EstadoEntrega.PENDIENTE;
import static com.restaurantes.delivery.domain.EstadoEntrega.RECOGIDO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EstadoEntregaTest {

    /** Tabla ESPERADA, escrita aparte de la implementacion: cada par (desde, hasta) permitido. */
    private static final Set<String> ESPERADAS = Set.of(
            "pendiente>asignado", "pendiente>fallido",
            "asignado>asignado", "asignado>recogido", "asignado>fallido",
            "recogido>entregado", "recogido>fallido",
            "fallido>devuelto_a_local");

    @Test
    void cadaParDeEstadosCoincideConLaTablaEsperada() {
        for (EstadoEntrega desde : EstadoEntrega.values()) {
            for (EstadoEntrega hasta : EstadoEntrega.values()) {
                assertEquals(ESPERADAS.contains(desde.valor() + ">" + hasta.valor()), EstadoEntrega.permitida(desde, hasta),
                        desde + " -> " + hasta);
            }
        }
    }

    @Test
    void entregadoYDevueltoALocalSonFinales() {
        assertEquals(true, ENTREGADO.esFinal());
        assertEquals(true, DEVUELTO_A_LOCAL.esFinal());
        for (EstadoEntrega e : new EstadoEntrega[] {PENDIENTE, ASIGNADO, RECOGIDO, FALLIDO}) {
            assertEquals(false, e.esFinal(), e.name());
        }
    }

    @Test
    void nadieSaleDeEntregado() {
        for (EstadoEntrega hasta : EstadoEntrega.values()) {
            assertEquals(false, EstadoEntrega.permitida(ENTREGADO, hasta), "entregado -> " + hasta);
        }
    }

    @Test
    void exigirLanzaConflictoConElCodigoDeNegocio() {
        ReglaDeNegocioException e = assertThrows(ReglaDeNegocioException.class, () -> EstadoEntrega.exigir(PENDIENTE, ENTREGADO));
        assertEquals("ENTREGA_TRANSICION_INVALIDA", e.codigo());
        EstadoEntrega.exigir(ASIGNADO, RECOGIDO);
    }

    @Test
    void losValoresCoincidenConElEsquema() {
        for (EstadoEntrega e : EstadoEntrega.values()) {
            assertEquals(e, EstadoEntrega.desde(e.valor()));
        }
        assertThrows(IllegalArgumentException.class, () -> EstadoEntrega.desde("volando"));
    }
}
