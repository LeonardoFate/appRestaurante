package com.restaurantes.cocina.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code POST /api/v1/cocina/items/{id}/anular}. El motivo es obligatorio y queda registrado. */
public record AnularItemRequest(@NotBlank @Size(max = 200) String motivo) {
}
