package com.restaurantes.shared.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Relay del outbox (ADR-003): publica los eventos pendientes a sus manejadores.
 *
 * <p>Lee con el datasource {@code scheduler} (varios restaurantes, privilegios minimos) usando
 * {@code FOR UPDATE SKIP LOCKED}, de modo que varias instancias del relay no se pisan. Cada manejador corre en su
 * propia transaccion DEL TENANT del evento, con la marca de deduplicacion en la misma transaccion: la entrega es
 * "al menos una vez" pero el efecto de cada consumidor ocurre exactamente una vez.
 *
 * <p>Un fallo de un manejador reprograma el evento con espera creciente (5 s, 10 s, 20 s... hasta 1 h); al
 * agotar {@code app.relay.max-intentos} deja de reintentarse y queda visible como pendiente con {@code intentos}
 * alto: nunca se descarta. Un evento sin manejadores se marca publicado.
 */
@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);

    private record Fila(UUID eventId, OffsetDateTime creadoEn, EventoDominio evento) {
    }

    @Inject
    @DataSource("scheduler")
    AgroalDataSource scheduler;

    @Inject
    TenantTransaction tx;

    @Inject
    Instance<ManejadorDeEvento> manejadores;

    @Inject
    ObjectMapper json;

    @ConfigProperty(name = "app.relay.lote")
    int lote;

    @ConfigProperty(name = "app.relay.max-intentos")
    int maxIntentos;

    /**
     * Publica un lote de eventos pendientes.
     *
     * @return cuantos eventos se intentaron en esta llamada (publicados o reprogramados por un fallo). Un valor igual
     *         al tamano del lote indica que probablemente quedan mas; 0 indica que no habia nada vencido
     */
    public int publicarLote() {
        try (Connection c = scheduler.getConnection()) {
            c.setAutoCommit(false);
            try {
                List<Fila> filas = reclamar(c);
                for (Fila fila : filas) {
                    Optional<String> error = despachar(fila.evento());
                    if (error.isEmpty()) {
                        marcarPublicado(c, fila);
                    } else {
                        marcarFallo(c, fila, error.get());
                    }
                }
                c.commit();
                return filas.size();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("Fallo el relay del outbox", e);
        }
    }

    // ------------------------------------------------------------- lectura

    private List<Fila> reclamar(Connection c) throws SQLException {
        // creado_en acota la busqueda y permite podar particiones (F-002): lo mas antiguo que se reintenta son 30 dias.
        try (PreparedStatement ps = c.prepareStatement("""
                select event_id, creado_en, tenant_id, tipo, version_esquema, agregado_tipo, agregado_id,
                       payload::text as payload
                  from outbox
                 where publicado_en is null and proximo_intento_en <= now() and intentos < ?
                   and creado_en > now() - interval '30 days'
                 order by creado_en
                 limit ?
                 for update skip locked
                """)) {
            ps.setInt(1, maxIntentos);
            ps.setInt(2, lote);
            List<Fila> filas = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        EventoDominio evento = new EventoDominio(rs.getObject("event_id", UUID.class),
                                rs.getObject("tenant_id", UUID.class), rs.getString("tipo"), rs.getInt("version_esquema"),
                                rs.getString("agregado_tipo"), rs.getObject("agregado_id", UUID.class),
                                json.readTree(rs.getString("payload")));
                        filas.add(new Fila(evento.eventId(), rs.getObject("creado_en", OffsetDateTime.class), evento));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Payload de evento no valido", e);
                    }
                }
            }
            return filas;
        }
    }

    // ------------------------------------------------------------ despacho

    /** @return vacio si todos los manejadores terminaron bien; si no, el motivo del primer fallo */
    private Optional<String> despachar(EventoDominio evento) {
        for (ManejadorDeEvento manejador : manejadores) {
            if (!manejador.tipoEvento().equals(evento.tipo())) {
                continue;
            }
            try {
                tx.runInTenant(evento.tenantId(), c -> {
                    if (registrarComoProcesado(c, manejador.consumidor(), evento)) {
                        manejador.manejar(evento);
                    }
                });
            } catch (RuntimeException e) {
                LOG.warnf("El manejador %s fallo con el evento %s (%s): %s", manejador.consumidor(), evento.eventId(),
                        evento.tipo(), e.getMessage());
                return Optional.of(manejador.consumidor() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Deduplicacion por consumidor, en la misma transaccion del manejador.
     *
     * @return {@code true} si es la primera vez que este consumidor ve el evento
     */
    private static boolean registrarComoProcesado(Connection c, String consumidor, EventoDominio evento) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                insert into eventos_procesados(consumidor, event_id, tenant_id) values (?, ?, ?)
                on conflict (consumidor, event_id) do nothing
                """)) {
            ps.setString(1, consumidor);
            ps.setObject(2, evento.eventId());
            ps.setObject(3, evento.tenantId());
            return ps.executeUpdate() == 1;
        }
    }

    // ---------------------------------------------------------- resultado

    private static void marcarPublicado(Connection c, Fila fila) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update outbox set publicado_en = now() where event_id = ? and creado_en = ?")) {
            ps.setObject(1, fila.eventId());
            ps.setObject(2, fila.creadoEn());
            ps.executeUpdate();
        }
    }

    private void marcarFallo(Connection c, Fila fila, String motivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                update outbox
                   set intentos = intentos + 1, ultimo_error = left(?, 1000),
                       proximo_intento_en = now() + make_interval(secs => least(3600, 5 * power(2, intentos)))
                 where event_id = ? and creado_en = ?
                """)) {
            ps.setString(1, motivo);
            ps.setObject(2, fila.eventId());
            ps.setObject(3, fila.creadoEn());
            ps.executeUpdate();
        }
    }
}
