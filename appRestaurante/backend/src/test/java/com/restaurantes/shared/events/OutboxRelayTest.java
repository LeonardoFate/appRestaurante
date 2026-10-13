package com.restaurantes.shared.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.restaurantes.flujo.Escenario;
import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.jobs.AdminScheduler;
import com.restaurantes.shared.tenancy.TenantTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El relay del outbox contra la base real (ADR-003). Se invoca a mano para que cada prueba sea determinista. */
@QuarkusTest
class OutboxRelayTest {

    @Inject
    Escenario escenario;

    @Inject
    OutboxRelay relay;

    @Inject
    Outbox outbox;

    @Inject
    TenantTransaction tx;

    @Inject
    AdminScheduler admin;

    private UUID emitir(UUID tenant, String tipo, Map<String, Object> payload) {
        return tx.withTenant(tenant, c -> outbox.emitir(tipo, "prueba", UuidV7.next(), payload));
    }

    /** Publica hasta que no quede nada vencido (el relay es global: puede haber eventos de otras pruebas). */
    private void drenar() {
        for (int i = 0; i < 50 && relay.publicarLote() > 0; i++) {
            // sigue
        }
    }

    private boolean publicado(UUID tenant, UUID evento) {
        return escenario.contar(tenant, "select count(*) from outbox where event_id = ? and publicado_en is not null", evento) == 1;
    }

    private void adelantar(UUID evento) {
        admin.ejecutar("update outbox set proximo_intento_en = now() where event_id = ?", evento);
    }

    @Test
    void entregaElEventoAlManejadorEnElTenantCorrecto() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        UUID eventoA = emitir(a.tenantId(), "PruebaEvento", Map.of("x", "a"));
        UUID eventoB = emitir(b.tenantId(), "PruebaEvento", Map.of("x", "b"));

        drenar();

        assertEquals(1, RegistroDeEventos.veces("prueba.ok", eventoA));
        assertEquals(1, RegistroDeEventos.veces("prueba.ok", eventoB));
        assertEquals(a.tenantId(), RegistroDeEventos.TENANT_DEL_EVENTO.get(eventoA));
        assertEquals(b.tenantId(), RegistroDeEventos.TENANT_DEL_EVENTO.get(eventoB));
        assertTrue(publicado(a.tenantId(), eventoA));
        assertTrue(publicado(b.tenantId(), eventoB));
    }

    @Test
    void unEventoSinManejadoresSeMarcaPublicado() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID evento = emitir(r.tenantId(), "TipoQueNadieEscucha", Map.of());

        drenar();

        assertTrue(publicado(r.tenantId(), evento));
    }

    @Test
    void elEventoSoloExisteSiSeConfirmaLaTransaccion() {
        Restaurante r = escenario.crearRestaurante("10.00");
        try {
            tx.runInTenant(r.tenantId(), c -> {
                outbox.emitir("PruebaEvento", "prueba", UuidV7.next(), Map.of());
                throw new IllegalStateException("el cambio de negocio fallo");
            });
        } catch (IllegalStateException esperado) {
            // el rollback arrastra tambien al evento
        }

        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from outbox"));
    }

    @Test
    void unaReentregaNoRepiteElEfecto() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID evento = emitir(r.tenantId(), "PruebaEvento", Map.of());
        drenar();
        assertEquals(1, RegistroDeEventos.veces("prueba.ok", evento));

        // Simula que el relay murio antes de confirmar la marca de publicado: el evento vuelve a quedar pendiente.
        admin.ejecutar("update outbox set publicado_en = null where event_id = ?", evento);
        drenar();

        assertEquals(1, RegistroDeEventos.veces("prueba.ok", evento), "la deduplicacion por consumidor evita repetir el efecto");
        assertTrue(publicado(r.tenantId(), evento), "pero el evento queda publicado otra vez");
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from eventos_procesados where consumidor = 'prueba.ok'"));
    }

    @Test
    void unFalloReintentaConEsperaCreciente() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID evento = emitir(r.tenantId(), "PruebaFalla", Map.of("exitoEnIntento", 2));

        drenar();

        assertEquals(false, publicado(r.tenantId(), evento));
        assertEquals(1, escenario.contar(r.tenantId(), "select intentos from outbox where event_id = ?", evento));
        assertTrue(escenario.texto(r.tenantId(), "select ultimo_error from outbox where event_id = ?", evento).contains("prueba.falla"));
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from outbox where event_id = ? and proximo_intento_en > now() + interval '3 seconds'", evento),
                "el primer reintento espera unos 5 segundos");

        drenar();
        assertEquals(1, RegistroDeEventos.veces("prueba.falla", evento), "aun no vence la espera: no se reintenta");

        adelantar(evento);
        drenar();

        assertTrue(publicado(r.tenantId(), evento));
        assertEquals(2, RegistroDeEventos.veces("prueba.falla", evento));
    }

    @Test
    void elFalloDeUnManejadorNoRepiteElEfectoDeLosOtros() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID evento = emitir(r.tenantId(), "PruebaDos", Map.of());

        drenar();
        assertEquals(false, publicado(r.tenantId(), evento), "B fallo: el evento sigue pendiente");
        adelantar(evento);
        drenar();

        assertTrue(publicado(r.tenantId(), evento));
        assertEquals(1, RegistroDeEventos.veces("prueba.a", evento), "A ya habia terminado: no se repite");
        assertEquals(2, RegistroDeEventos.veces("prueba.b", evento));
    }

    @Test
    void siFallaElManejadorSeRevierteSuEfectoYLaMarcaDeProcesado() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID evento = emitir(r.tenantId(), "PruebaEfecto", Map.of());

        drenar();
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from notificaciones"), "el efecto se revirtio");
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from eventos_procesados where consumidor = 'prueba.efecto'"),
                "y tambien la marca de procesado: asi el reintento SI ejecuta el efecto");

        adelantar(evento);
        drenar();

        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from notificaciones"), "el efecto ocurre exactamente una vez");
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from eventos_procesados where consumidor = 'prueba.efecto'"));
        assertTrue(publicado(r.tenantId(), evento));
    }

    @Test
    void unEventoQueAgotoSusIntentosYaNoSeReintenta() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID evento = emitir(r.tenantId(), "PruebaEvento", Map.of());
        admin.ejecutar("update outbox set intentos = 10 where event_id = ?", evento);   // app.relay.max-intentos = 10

        drenar();

        assertEquals(0, RegistroDeEventos.veces("prueba.ok", evento));
        assertEquals(false, publicado(r.tenantId(), evento), "queda pendiente y visible (intentos alto), nunca se descarta");
    }
}
