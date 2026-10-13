package com.restaurantes.pagos.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cuerpo de {@code POST /api/v1/pagos/{pagoId}/reembolsos}. {@code id} (UUIDv7 del cliente) hace idempotente el reintento.
 * El motivo es obligatorio y queda en el reembolso, el historial del pedido y la auditoria.
 */
public record ReembolsarRequest(
        UUID id,
        @NotNull @Positive BigDecimal monto,
        @NotBlank @Size(max = 200) String motivo) {
}
