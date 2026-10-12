package com.restaurantes.auth.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Cuerpo de {@code POST /api/v1/auth/login}. {@code tenantId} y {@code sucursalId} solo hacen falta si el usuario
 * pertenece a varios restaurantes o a varias sucursales.
 */
public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        // El limite protege al servidor: Argon2 cuesta CPU y memoria proporcionales al trabajo, no al largo, pero conviene acotar.
        @NotBlank @Size(max = 128) String password,
        UUID tenantId,
        UUID sucursalId) {
}
