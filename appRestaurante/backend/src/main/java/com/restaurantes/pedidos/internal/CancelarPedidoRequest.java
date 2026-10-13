package com.restaurantes.pedidos.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code POST /api/v1/pedidos/{id}/cancelar}. El motivo es obligatorio y queda en el historial. */
public record CancelarPedidoRequest(@NotBlank @Size(max = 200) String motivo) {
}
