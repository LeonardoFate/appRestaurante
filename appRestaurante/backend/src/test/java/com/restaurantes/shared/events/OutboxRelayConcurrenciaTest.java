package com.restaurantes.shared.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.restaurantes.flujo.Escenario;
import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.flujo.PoolGrande;
import com.restaurantes.shared.ids.UuidV7;
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

/** Varios relays a la vez: cada evento debe entregarse a su consumidor exactamente una vez. */
@QuarkusTest
@TestProfile(PoolGrande.class)
class OutboxRelayConcurrenciaTest {

    @Inject
    Escenario escenario;

    @Inject
    OutboxRelay relay;

    @Inject
    Outbox outbox;

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
    void variosRelaysALaVezEntreganCadaEventoUnaSolaVez() throws Exception {
        List<Restaurante> restaurantes = List.of(escenario.crearRestaurante("10.00"), escenario.crearRestaurante("10.00"),
                escenario.crearRestaurante("10.00"));
        List<UUID> eventos = new ArrayList<>();
        for (Restaurante r : restaurantes) {
            for (int i = 0; i < 15; i++) {
                eventos.add(tx.withTenant(r.tenantId(), c -> outbox.emitir("PruebaEvento", "prueba", UuidV7.next(), Map.of())));
            }
        }

        // 5 relays compiten por los 45 eventos.
        simultaneamente(5, () -> {
            int n;
            int total = 0;
            while ((n = relay.publicarLote()) > 0) {
                total += n;
            }
            return total;
        });

        for (UUID evento : eventos) {
            assertEquals(1, RegistroDeEventos.veces("prueba.ok", evento), "el evento " + evento + " debe entregarse una sola vez");
        }
        for (Restaurante r : restaurantes) {
            assertEquals(15, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'PruebaEvento' and publicado_en is not null"));
            assertEquals(15, escenario.contar(r.tenantId(), "select count(*) from eventos_procesados where consumidor = 'prueba.ok'"));
        }
    }
}
