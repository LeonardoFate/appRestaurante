package com.restaurantes.shared.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Evento de dominio tal como llega a un manejador. {@code eventId} identifica el evento y es la clave con la que se
 * deduplican las reentregas (la entrega es "al menos una vez").
 */
public record EventoDominio(
        UUID eventId,
        UUID tenantId,
        String tipo,
        int versionEsquema,
        String agregadoTipo,
        UUID agregadoId,
        JsonNode payload) {
}
