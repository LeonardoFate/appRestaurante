package com.restaurantes.shared.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.restaurantes.flujo.Escenario;
import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.tenancy.TenantTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La cola de trabajos contra la base real (ADR-015). El worker se invoca a mano para que cada prueba sea determinista. */
@QuarkusTest
class JobsTest {

    @Inject
    Escenario escenario;

    @Inject
    ColaDeJobs cola;

    @Inject
    JobWorker worker;

    @Inject
    TenantTransaction tx;

    @Inject
    AdminScheduler admin;

    private UUID encolar(UUID tenant, String tipo, Map<String, Object> payload, String clave, int maxIntentos) {
        return tx.withTenant(tenant, c -> cola.encolar("prueba", tipo, payload, clave, maxIntentos)).orElseThrow();
    }

    private UUID encolar(UUID tenant, String tipo, Map<String, Object> payload) {
        return encolar(tenant, tipo, payload, null, 5);
    }

    /** Procesa hasta que no quede nada vencido (el worker es global: puede haber trabajos de otras pruebas). */
    private void drenar() {
        for (int i = 0; i < 50 && worker.procesarLote() > 0; i++) {
            // sigue
        }
    }

    private String estado(UUID tenant, UUID job) {
        return escenario.texto(tenant, "select estado from jobs where id = ?", job);
    }

    private long intentos(UUID tenant, UUID job) {
        return escenario.contar(tenant, "select intentos from jobs where id = ?", job);
    }

    /** Vuelve vencido un job reprogramado, como si hubiera pasado el tiempo de espera. */
    private void adelantar(UUID job) {
        admin.ejecutar("update jobs set proximo_intento_en = now() where id = ?", job);
    }

    @Test
    void completaUnJobYLoMarcaComoCompletado() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.ok", Map.of());

        drenar();

        assertEquals("completado", estado(r.tenantId(), job));
        assertEquals(1, intentos(r.tenantId(), job));
        assertEquals(1, RegistroDeEjecuciones.veces(job));
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from jobs where id = ? and completado_en is not null and bloqueado_por is null and bloqueado_hasta is null", job));
    }

    @Test
    void elProcesadorCorreEnElTenantDelJobYSoloVeSusDatos() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        UUID jobA = encolar(a.tenantId(), "prueba.tenant", Map.of());
        UUID jobB = encolar(b.tenantId(), "prueba.tenant", Map.of());

        drenar();

        assertEquals(a.tenantId(), RegistroDeEjecuciones.TENANT_DE_LA_EJECUCION.get(jobA));
        assertEquals(b.tenantId(), RegistroDeEjecuciones.TENANT_DE_LA_EJECUCION.get(jobB));
        assertEquals(1L, RegistroDeEjecuciones.JOBS_VISIBLES_DURANTE_LA_EJECUCION.get(jobA),
                "dentro de su transaccion cada job solo ve los jobs de su restaurante (RLS)");
        assertEquals(1L, RegistroDeEjecuciones.JOBS_VISIBLES_DURANTE_LA_EJECUCION.get(jobB));
    }

    @Test
    void unFalloReprogramaConEsperaCreciente() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.falla", Map.of("exitoEnIntento", 2));

        drenar();

        assertEquals("pendiente", estado(r.tenantId(), job));
        assertEquals(1, intentos(r.tenantId(), job));
        assertTrue(escenario.texto(r.tenantId(), "select ultimo_error from jobs where id = ?", job).contains("fallo simulado 1"));
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from jobs where id = ? and proximo_intento_en > now() + interval '3 seconds'", job),
                "el primer reintento espera unos 5 segundos");

        drenar();
        assertEquals(1, RegistroDeEjecuciones.veces(job), "aun no vence la espera: no se reintenta");

        adelantar(job);
        drenar();

        assertEquals("completado", estado(r.tenantId(), job));
        assertEquals(2, intentos(r.tenantId(), job));
        assertEquals(2, RegistroDeEjecuciones.veces(job));
    }

    @Test
    void laEsperaCreceEnCadaReintento() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.falla", Map.of("exitoEnIntento", 0), null, 5);

        drenar();
        long espera1 = Long.parseLong(escenario.texto(r.tenantId(),
                "select extract(epoch from (proximo_intento_en - now()))::int from jobs where id = ?", job));
        adelantar(job);
        drenar();
        long espera2 = Long.parseLong(escenario.texto(r.tenantId(),
                "select extract(epoch from (proximo_intento_en - now()))::int from jobs where id = ?", job));

        assertTrue(espera1 >= 3 && espera1 <= 5, "5 s tras el primer fallo, fue " + espera1);
        assertTrue(espera2 >= 8 && espera2 <= 10, "10 s tras el segundo fallo, fue " + espera2);
    }

    @Test
    void alAgotarLosIntentosPasaAFallidoYYaNoSeReintenta() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.falla", Map.of("exitoEnIntento", 0), null, 2);

        drenar();
        assertEquals("pendiente", estado(r.tenantId(), job));
        adelantar(job);
        drenar();

        assertEquals("fallido", estado(r.tenantId(), job), "agotados los intentos queda como trabajo muerto, visible");
        assertEquals(2, intentos(r.tenantId(), job));
        assertNotNull(escenario.texto(r.tenantId(), "select ultimo_error from jobs where id = ?", job));

        adelantar(job);
        drenar();
        assertEquals(2, RegistroDeEjecuciones.veces(job), "un trabajo fallido no se vuelve a ejecutar");
    }

    @Test
    void unFalloPermanenteVaDirectoAFallido() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.permanente", Map.of());

        drenar();

        assertEquals("fallido", estado(r.tenantId(), job));
        assertEquals(1, intentos(r.tenantId(), job), "sin reintentos");
        assertTrue(escenario.texto(r.tenantId(), "select ultimo_error from jobs where id = ?", job).contains("dato invalido simulado"));
    }

    @Test
    void siElProcesadorFallaSeRevierteTodoLoQueEscribio() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.efecto", Map.of("falla", true));

        drenar();

        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from notificaciones"), "el efecto se revirtio");
        assertEquals("pendiente", estado(r.tenantId(), job));
    }

    @Test
    void elEfectoYLaMarcaDeCompletadoSeConfirmanJuntos() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.efecto", Map.of("falla", false));

        drenar();

        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from notificaciones"));
        assertEquals("completado", estado(r.tenantId(), job));
    }

    @Test
    void encolarConLaMismaClaveNoDuplica() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");

        Optional<UUID> primero = tx.withTenant(a.tenantId(), c -> cola.encolar("prueba", "prueba.ok", Map.of(), "clave-1", 5));
        Optional<UUID> repetido = tx.withTenant(a.tenantId(), c -> cola.encolar("prueba", "prueba.ok", Map.of(), "clave-1", 5));
        Optional<UUID> otroTenant = tx.withTenant(b.tenantId(), c -> cola.encolar("prueba", "prueba.ok", Map.of(), "clave-1", 5));

        assertTrue(primero.isPresent());
        assertTrue(repetido.isEmpty(), "la misma clave en la misma cola y restaurante no crea otro job");
        assertTrue(otroTenant.isPresent(), "otro restaurante puede usar la misma clave");
        assertEquals(1, escenario.contar(a.tenantId(), "select count(*) from jobs where clave_unica = 'clave-1'"));
    }

    @Test
    void elJobSeEncolaSoloSiSeConfirmaLaTransaccion() {
        Restaurante r = escenario.crearRestaurante("10.00");

        try {
            tx.runInTenant(r.tenantId(), c -> {
                cola.encolar("prueba", "prueba.ok", Map.of(), "no-se-confirma", 5);
                throw new IllegalStateException("el cambio de negocio fallo");
            });
        } catch (IllegalStateException esperado) {
            // el rollback arrastra tambien al job
        }

        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from jobs"));
    }

    @Test
    void unTipoSinProcesadorNoSeReclama() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "desconocido.xyz", Map.of());

        drenar();

        assertEquals("pendiente", estado(r.tenantId(), job));
        assertEquals(0, intentos(r.tenantId(), job), "esperando a que exista su procesador (p. ej. sri.emitir)");
    }

    // ---------------------------------------------------------- lease y worker caido

    @Test
    void unLeaseVencidoPermiteQueOtroWorkerReclameElJob() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.ok", Map.of());
        // Un worker lo reclamo y murio: lease vencido hace un minuto.
        admin.ejecutar("update jobs set estado = 'en_proceso', intentos = 1, bloqueado_hasta = now() - interval '1 minute', "
                + "bloqueado_por = 'worker-muerto' where id = ?", job);

        drenar();

        assertEquals("completado", estado(r.tenantId(), job));
        assertEquals(2, intentos(r.tenantId(), job));
        assertEquals(1, RegistroDeEjecuciones.veces(job));
    }

    @Test
    void unLeaseVigenteNoSeReclama() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.ok", Map.of());
        admin.ejecutar("update jobs set estado = 'en_proceso', intentos = 1, bloqueado_hasta = now() + interval '5 minutes', "
                + "bloqueado_por = 'worker-vivo' where id = ?", job);

        drenar();

        assertEquals("en_proceso", estado(r.tenantId(), job));
        assertEquals(0, RegistroDeEjecuciones.veces(job), "otro worker lo esta procesando");
    }

    @Test
    void siElWorkerMurioEnSuUltimoIntentoElJobPasaAFallido() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.ok", Map.of(), null, 3);
        admin.ejecutar("update jobs set estado = 'en_proceso', intentos = 3, bloqueado_hasta = now() - interval '1 minute', "
                + "bloqueado_por = 'worker-muerto' where id = ?", job);

        drenar();

        assertEquals("fallido", estado(r.tenantId(), job), "no se ejecuta un cuarto intento: queda visible para revision");
        assertEquals(0, RegistroDeEjecuciones.veces(job));
        assertTrue(escenario.texto(r.tenantId(), "select ultimo_error from jobs where id = ?", job).contains("worker se detuvo"));
    }

    @Test
    void unWorkerQuePierdeElLeaseNoCompletaNiDejaEfectos() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID job = encolar(r.tenantId(), "prueba.robo", Map.of());

        drenar();

        // Otro worker se quedo con el lease mientras este procesaba: este NO puede completar ni dejar su efecto.
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from notificaciones"), "su efecto se revirtio");
        assertEquals("en_proceso", estado(r.tenantId(), job));
        assertEquals("otro-worker", escenario.texto(r.tenantId(), "select bloqueado_por from jobs where id = ?", job),
                "el lease sigue siendo del otro worker");
        assertNull(escenario.texto(r.tenantId(), "select completado_en::text from jobs where id = ?", job));
    }
}
