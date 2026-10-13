package com.restaurantes.shared.jobs;

/**
 * Procesa un tipo de trabajo de la cola (ADR-015).
 *
 * <p>Se ejecuta DENTRO de una unidad de trabajo del tenant del job ({@code TenantTransaction}): lo que escriba se
 * confirma junto con la marca de "completado", o se revierte todo si lanza una excepcion. Aun asi, debe ser
 * idempotente: la entrega es "al menos una vez" (por ejemplo, si el proceso muere tras confirmar y antes de responder).
 *
 * <p>Si lanza {@link JobPermanenteException} el trabajo pasa a "fallido" sin reintentos; cualquier otra excepcion
 * lo reprograma con espera creciente hasta agotar sus intentos.
 */
public interface ProcesadorDeJob {

    /** Tipo de job que maneja (por ejemplo {@code sri.emitir}). */
    String tipo();

    void procesar(JobContexto contexto);
}
