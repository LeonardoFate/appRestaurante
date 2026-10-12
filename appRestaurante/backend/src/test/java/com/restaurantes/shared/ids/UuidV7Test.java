package com.restaurantes.shared.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void tieneVersion7YVarianteRfc() {
        UUID id = UuidV7.next();
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
        // Posicion 15 del texto = version (la misma comprobacion que hace la base).
        assertEquals('7', id.toString().charAt(14));
    }

    @Test
    void codificaElInstanteEnMilisegundos() {
        long ahora = System.currentTimeMillis();
        UUID id = UuidV7.from(ahora);
        assertEquals(Instant.ofEpochMilli(ahora), UuidV7.timestamp(id));
    }

    @Test
    void seOrdenaPorTiempo() {
        UUID antes = UuidV7.from(1_700_000_000_000L);
        UUID despues = UuidV7.from(1_700_000_000_001L);
        assertTrue(antes.toString().compareTo(despues.toString()) < 0);
    }

    @Test
    void noRepiteIdsEnUnMismoMilisegundo() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            ids.add(UuidV7.from(1_700_000_000_000L));
        }
        assertEquals(50_000, ids.size());
    }

    @Test
    void rechazaUuidQueNoEsV7() {
        assertThrows(IllegalArgumentException.class, () -> UuidV7.timestamp(UUID.randomUUID()));
    }
}
