package com.restaurantes.auth.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code POST /api/v1/auth/refresh} y de {@code POST /api/v1/auth/logout}. */
public record RefreshRequest(@NotBlank @Size(max = 300) String refreshToken) {
}
