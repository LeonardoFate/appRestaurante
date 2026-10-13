package com.restaurantes.shared.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.restaurantes.flujo.Escenario;
import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.flujo.PoolGrande;
import com.restaurantes.shared.tenancy.TenantTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Varios workers a la vez: cada trabajo debe ejecutarse exactamente una vez. */
@QuarkusTest
@TestProfile(PoolGrande.class)
class JobsConcurrenciaTest {

    @Inject
    Escenario escenario;

    @Inject
    ColaDeJobs cola;

    @Inject
    JobWorker worker;

    @Inject
    TenantTransaction tx;

    private static <T> List<T> simultaneamente(int n, Callable<T> tarea) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch listos = new CountDownLatch(n);
        CountDownLatch ya = new CountDownLatch(1);
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    ya.await();
                    return tarea.call();
                }));
            }
            listos.await();
            ya.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> f : futuros) {
                resultados.add(f.get());
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void variosWorkersALaVezEjecutanCadaTrabajoExactamenteUnaVez() throws Exception {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        Restaurante c = escenario.crearRestaurante("10.00");
        List<UUID> jobs = new ArrayList<>();
        for (Restaurante r : List.of(a, b, c)) {
            for (int i = 0; i < 20; i++) {
                jobs.add(tx.withTenant(r.tenantId(), conn -> cola.encolar("prueba", "prueba.ok", Map.of(), null, 5)).orElseThrow());
            }
        }

        // 6 workers compiten por los 60 trabajos.
        simultaneamente(6, () -> {
            int total = 0;
            int n;
            while ((n = worker.procesarLote()) > 0) {
                total += n;
            }
            return total;
        });

        for (UUID job : jobs) {
            assertEquals(1, RegistroDeEjecuciones.veces(job), "el job " + job + " debe ejecutarse una sola vez");
        }
        for (Restaurante r : List.of(a, b, c)) {
            assertEquals(20, escenario.contar(r.tenantId(), "select count(*) from jobs where estado = 'completado'"));
            assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from jobs where estado <> 'completado'"));
        }
    }
}
