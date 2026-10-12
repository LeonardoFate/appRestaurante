package com.restaurantes.auth.internal;

import java.util.Set;
import java.util.UUID;

/** Sesion abierta: el JWT de acceso (corto) y el refresh token (de un solo uso) para renovarlo. */
public record SesionResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        UUID tenantId,
        UUID sucursalId,
        Set<String> roles) {
}
