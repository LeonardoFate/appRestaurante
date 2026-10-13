package com.restaurantes.pedidos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class EstadoCocinaTest {

    /** Transiciones validas de un item, escritas "desde>hasta" (6 x 6 combinaciones). */
    private static final Set<String> ESPERADAS = Set.of(
            "pendiente>enviado", "pendiente>anulado",
            "enviado>preparando", "enviado>listo", "enviado>anulado",
            "preparando>listo", "preparando>anulado",
            "listo>servido", "listo>anulado");

    @Test
    void sigueLaTablaParPorPar() {
        for (EstadoCocina desde : EstadoCocina.values()) {
            for (EstadoCocina hasta : EstadoCocina.values()) {
                assertEquals(ESPERADAS.contains(desde.valor() + ">" + hasta.valor()), desde.puedePasarA(hasta),
                        desde.valor() + " -> " + hasta.valor());
            }
        }
    }

    @Test
    void unItemServidoOAnuladoYaNoCambia() {
        for (EstadoCocina destino : EstadoCocina.values()) {
            assertFalse(EstadoCocina.SERVIDO.puedePasarA(destino));
            assertFalse(EstadoCocina.ANULADO.puedePasarA(destino));
        }
    }

    @Test
    void unItemServidoNoSePuedeAnular() {
        assertFalse(EstadoCocina.SERVIDO.puedePasarA(EstadoCocina.ANULADO));
        assertTrue(EstadoCocina.LISTO.puedePasarA(EstadoCocina.ANULADO));
    }

    @Test
    void soloLosAnuladosNoSonActivos() {
        for (EstadoCocina estado : EstadoCocina.values()) {
            assertEquals(estado != EstadoCocina.ANULADO, estado.estaActivo());
        }
    }

    @Test
    void convierteLosValoresDeBaseDeDatos() {
        for (EstadoCocina estado : EstadoCocina.values()) {
            assertEquals(estado, EstadoCocina.desde(estado.valor()));
        }
    }
}
