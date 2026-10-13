package com.restaurantes.shared.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Worker de la cola de trabajos (ADR-015).
 *
 * <p>Reclama trabajos de TODOS los restaurantes con el datasource {@code scheduler} (rol {@code app_scheduler},
 * privilegios minimos) y ejecuta cada uno dentro de la unidad de trabajo de SU tenant con el datasource normal.
 *
 * <p>Garantias:
 * <ul>
 *   <li>Varias instancias no duplican trabajo: se reclama con {@code FOR UPDATE SKIP LOCKED}.</li>
 *   <li>El efecto del procesador y la marca "completado" ocurren en la misma transaccion (o ninguno).</li>
 *   <li>Lease: un trabajo reclamado pertenece a un worker hasta {@code bloqueado_hasta}; si el worker muere,
 *       otro lo toma al vencer. Completar exige seguir siendo el dueno, asi un worker lento no pisa a otro.</li>
 *   <li>Reintentos con espera creciente (5 s, 10 s, 20 s... hasta 1 h); al agotarlos pasa a {@code fallido}, que
 *       funciona como cola de trabajos muertos. Nunca se descarta un trabajo en silencio.</li>
 *   <li>Solo se reclaman los tipos que tienen procesador registrado.</li>
 * </ul>
 */
@ApplicationScoped
public class JobWorker {

    private static final Logger LOG = Logger.getLogger(JobWorker.class);

    /** Una operacion de completar o fallar no se pudo aplicar porque el worker ya no es el dueno del trabajo. */
    private static final class LeasePerdidaException extends RuntimeException {
        LeasePerdidaException(UUID jobId) {
            super("El trabajo " + jobId + " ya no pertenece a este worker (lease vencido y reclamado por otro)");
        }
    }

    private record JobReclamado(UUID id, UUID tenantId, String tipo, String payload, int intentos) {
    }

    @Inject
    @DataSource("scheduler")
    AgroalDataSource scheduler;

    @Inject
    TenantTransaction tx;

    @Inject
    Instance<ProcesadorDeJob> procesadores;

    @Inject
    ObjectMapper json;

    @ConfigProperty(name = "app.jobs.lote")
    int lote;

    @ConfigProperty(name = "app.jobs.lease-segundos")
    int leaseSegundos;

    private final String instancia = UUID.randomUUID().toString();

    /**
     * Reclama y procesa un lote de trabajos vencidos.
     *
     * @return cuantos trabajos se ejecutaron en esta llamada (con exito o no). Un valor igual al tamano del lote
     *         indica que probablemente quedan mas; 0 indica que no habia nada vencido
     */
    public int procesarLote() {
        Map<String, ProcesadorDeJob> porTipo = new HashMap<>();
        for (ProcesadorDeJob p : procesadores) {
            porTipo.put(p.tipo(), p);
        }
        if (porTipo.isEmpty()) {
            return 0;
        }
        marcarAbandonados(porTipo.keySet());

        String dueno = instancia + ":" + UUID.randomUUID();
        List<JobReclamado> reclamados = reclamar(porTipo.keySet(), dueno);
        for (JobReclamado job : reclamados) {
            ejecutar(porTipo.get(job.tipo()), job, dueno);
        }
        return reclamados.size();
    }

    // ------------------------------------------------------------ ejecucion

    private boolean ejecutar(ProcesadorDeJob procesador, JobReclamado job, String dueno) {
        try {
            JsonNode payload = json.readTree(job.payload());
            tx.runInTenant(job.tenantId(), c -> {
                procesador.procesar(new JobContexto(job.id(), job.tenantId(), job.tipo(), payload, job.intentos()));
                completar(c, job.id(), dueno);
            });
            return true;
        } catch (LeasePerdidaException e) {
            LOG.warn(e.getMessage());
            return false;
        } catch (JobPermanenteException e) {
            registrarFallo(job, dueno, e, true);
            return false;
        } catch (RuntimeException | JsonProcessingException e) {
            registrarFallo(job, dueno, e, false);
            return false;
        }
    }

    /** Marca completado dentro de la transaccion del procesador. Falla si el lease ya no es de este worker. */
    private static void completar(Connection c, UUID jobId, String dueno) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                update jobs set estado = 'completado', completado_en = now(), bloqueado_hasta = null, bloqueado_por = null
                 where id = ? and estado = 'en_proceso' and bloqueado_por = ?
                """)) {
            ps.setObject(1, jobId);
            ps.setString(2, dueno);
            if (ps.executeUpdate() == 0) {
                throw new LeasePerdidaException(jobId);
            }
        }
    }

    // ------------------------------------------------------------- reclamo

    private List<JobReclamado> reclamar(java.util.Set<String> tipos, String dueno) {
        try (Connection c = scheduler.getConnection()) {
            Array arrayTipos = c.createArrayOf("text", tipos.toArray());
            try (PreparedStatement ps = c.prepareStatement("""
                    update jobs j
                       set estado = 'en_proceso', intentos = j.intentos + 1,
                           bloqueado_hasta = now() + make_interval(secs => ?::double precision), bloqueado_por = ?
                     where j.id in (
                           select id from jobs
                            where tipo = any (?)
                              and intentos < max_intentos
                              and ((estado = 'pendiente' and proximo_intento_en <= now())
                                or (estado = 'en_proceso' and bloqueado_hasta < now()))
                            order by proximo_intento_en
                            limit ?
                            for update skip locked)
                    returning j.id, j.tenant_id, j.tipo, j.payload::text as payload, j.intentos
                    """)) {
                ps.setInt(1, leaseSegundos);
                ps.setString(2, dueno);
                ps.setArray(3, arrayTipos);
                ps.setInt(4, lote);
                List<JobReclamado> reclamados = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        reclamados.add(new JobReclamado(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                                rs.getString("tipo"), rs.getString("payload"), rs.getInt("intentos")));
                    }
                }
                return reclamados;
            } finally {
                arrayTipos.free();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron reclamar trabajos", e);
        }
    }

    /** Un trabajo cuyo worker murio en su ultimo intento no puede reclamarse otra vez: pasa a fallido para que alguien lo vea. */
    private void marcarAbandonados(java.util.Set<String> tipos) {
        try (Connection c = scheduler.getConnection()) {
            Array arrayTipos = c.createArrayOf("text", tipos.toArray());
            try (PreparedStatement ps = c.prepareStatement("""
                    update jobs set estado = 'fallido', ultimo_error = 'El worker se detuvo en el ultimo intento (lease vencido)',
                                    bloqueado_hasta = null, bloqueado_por = null
                     where tipo = any (?) and estado = 'en_proceso' and bloqueado_hasta < now() and intentos >= max_intentos
                    """)) {
                ps.setArray(1, arrayTipos);
                int abandonados = ps.executeUpdate();
                if (abandonados > 0) {
                    LOG.errorf("%d trabajo(s) pasaron a 'fallido' porque su worker se detuvo en el ultimo intento", abandonados);
                }
            } finally {
                arrayTipos.free();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron revisar los trabajos abandonados", e);
        }
    }

    // --------------------------------------------------------------- fallos

    /**
     * Reprograma o da por fallido un trabajo cuya transaccion ya se revirtio. Solo lo hace el dueno actual del lease.
     * La espera crece 5 s, 10 s, 20 s... con tope de 1 hora.
     */
    private void registrarFallo(JobReclamado job, String dueno, Exception error, boolean permanente) {
        String mensaje = error.getClass().getSimpleName() + ": " + error.getMessage();
        try (Connection c = scheduler.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     update jobs
                        set estado = case when ? or intentos >= max_intentos then 'fallido' else 'pendiente' end,
                            proximo_intento_en = now() + make_interval(secs => least(3600, 5 * power(2, greatest(intentos - 1, 0)))),
                            ultimo_error = left(?, 1000), bloqueado_hasta = null, bloqueado_por = null
                      where id = ? and estado = 'en_proceso' and bloqueado_por = ?
                     returning estado
                     """)) {
            ps.setBoolean(1, permanente);
            ps.setString(2, mensaje);
            ps.setObject(3, job.id());
            ps.setString(4, dueno);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && "fallido".equals(rs.getString(1))) {
                    LOG.errorf(error, "Trabajo %s (%s, tenant %s) paso a 'fallido' tras %d intento(s)",
                            job.id(), job.tipo(), job.tenantId(), job.intentos());
                } else {
                    LOG.warnf("Trabajo %s (%s) fallo en el intento %d y se reprogramo: %s", job.id(), job.tipo(), job.intentos(), mensaje);
                }
            }
        } catch (SQLException e) {
            // Si no se puede registrar el fallo, el lease vencera y otro worker lo reclamara: no se pierde.
            LOG.errorf(e, "No se pudo registrar el fallo del trabajo %s", job.id());
        }
    }
}
