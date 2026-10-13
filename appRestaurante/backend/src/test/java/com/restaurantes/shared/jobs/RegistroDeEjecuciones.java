package com.restaurantes.shared.jobs;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Memoria compartida de las pruebas: cuantas veces y en que tenant se ejecuto cada job. */
public final class RegistroDeEjecuciones {

    public static final Map<UUID, AtomicInteger> EJECUCIONES = new ConcurrentHashMap<>();
    public static final Map<UUID, UUID> TENANT_DE_LA_EJECUCION = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> JOBS_VISIBLES_DURANTE_LA_EJECUCION = new ConcurrentHashMap<>();

    private RegistroDeEjecuciones() {
    }

    static void registrar(UUID jobId) {
        EJECUCIONES.computeIfAbsent(jobId, k -> new AtomicInteger()).incrementAndGet();
    }

    public static int veces(UUID jobId) {
        AtomicInteger n = EJECUCIONES.get(jobId);
        return n == null ? 0 : n.get();
    }
}
