package com.restaurantes.auth.domain;

import java.util.Set;
import java.util.UUID;

/**
 * Restaurante, sucursal y roles con los que se emite un token. Los roles son la union de todas las
 * membresias del usuario que aplican a esa sucursal.
 */
public record AccesoElegido(UUID tenantId, UUID sucursalId, boolean sucursalPorConfirmar, Set<String> roles) {
}
