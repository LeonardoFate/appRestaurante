package com.restaurantes.auth.domain;

import java.util.UUID;

/**
 * Pertenencia activa de un usuario a un restaurante con un rol.
 *
 * @param sucursalId {@code null} significa "todas las sucursales del restaurante"
 */
public record Membresia(UUID tenantId, UUID sucursalId, String rol) {
}
