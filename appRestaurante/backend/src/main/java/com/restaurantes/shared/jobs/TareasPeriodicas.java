package com.restaurantes.shared.jobs;

import com.restaurantes.shared.events.OutboxRelay;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Ejecucion periodica del relay del outbox y del worker de la cola de trabajos. {@code concurrentExecution = SKIP}
 * evita solapar dos ejecuciones en la misma instancia; entre instancias protege {@code SKIP LOCKED}.
 * En las pruebas el planificador esta desactivado y se invoca cada pieza a mano.
 */
@ApplicationScoped
public class TareasPeriodicas {

    private static final Logger LOG = Logger.getLogger(TareasPeriodicas.class);

    @Inject
    OutboxRelay relay;

    @Inject
    JobWorker worker;

    @ConfigProperty(name = "app.relay.lote")
    int loteRelay;

    @ConfigProperty(name = "app.jobs.lote")
    int loteJobs;

    @Scheduled(every = "{app.relay.intervalo}", identity = "outbox-relay", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void relay() {
        try {
            // Si el lote vino lleno, hay mas pendientes: se sigue hasta vaciar (con tope para no monopolizar el hilo).
            for (int i = 0; i < 20 && relay.publicarLote() >= loteRelay; i++) {
                // continua
            }
        } catch (RuntimeException e) {
            LOG.error("Fallo el relay del outbox", e);
        }
    }

    @Scheduled(every = "{app.jobs.intervalo}", identity = "cola-de-trabajos", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void trabajos() {
        try {
            for (int i = 0; i < 20 && worker.procesarLote() >= loteJobs; i++) {
                // continua
            }
        } catch (RuntimeException e) {
            LOG.error("Fallo el worker de la cola de trabajos", e);
        }
    }
}
