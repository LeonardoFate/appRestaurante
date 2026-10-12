package com.restaurantes.shared.ids;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): 48 bits de milisegundos Unix + version 7 + variante 10 + aleatorio.
 *
 * <p>Equivale a {@code uuid_v7()} del esquema SQL (ADR-004). La base exige version 7 en los
 * IDs generados por el cliente (pedidos, items, pagos...), asi que todo ID nuevo sale de aqui.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        return from(System.currentTimeMillis());
    }

    static UUID from(long epochMillis) {
        long msb = ((epochMillis & 0xFFFFFFFFFFFFL) << 16)
                | 0x7000L
                | (RANDOM.nextLong() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    /** Instante codificado en un UUIDv7. */
    public static Instant timestamp(UUID id) {
        if (id.version() != 7) {
            throw new IllegalArgumentException("No es un UUIDv7: " + id);
        }
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }
}
