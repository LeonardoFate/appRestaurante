package com.restaurantes.shared.events;

/**
 * Reacciona a un tipo de evento de dominio publicado por el outbox (ADR-001, ADR-003).
 *
 * <p>El relay lo invoca DENTRO de una unidad de trabajo del tenant del evento y deduplica por
 * {@code (consumidor, eventId)}: lo que haga el manejador se confirma junto con la marca "procesado" o se revierte
 * todo. Si lanza una excepcion el relay reintentara con espera creciente.
 */
public interface ManejadorDeEvento {

    /** Nombre estable del consumidor; es parte de la clave de deduplicacion, asi que no debe cambiar. */
    String consumidor();

    /** Tipo de evento que maneja (por ejemplo {@code PedidoPagado}). */
    String tipoEvento();

    void manejar(EventoDominio evento);
}
