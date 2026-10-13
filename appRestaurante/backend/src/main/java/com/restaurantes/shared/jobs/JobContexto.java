package com.restaurantes.shared.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Datos del job que se esta procesando.
 *
 * @param intento numero de intento actual (1 la primera vez)
 */
public record JobContexto(UUID jobId, UUID tenantId, String tipo, JsonNode payload, int intento) {
}
