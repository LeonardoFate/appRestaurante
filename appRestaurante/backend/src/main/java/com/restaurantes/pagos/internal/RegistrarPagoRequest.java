package com.restaurantes.pagos.internal;

import com.restaurantes.pagos.domain.MetodoPago;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/** Cuerpo de {@code POST /api/v1/pagos}. {@code id} (UUIDv7 del cliente) hace idempotente el reintento. */
public record RegistrarPagoRequest(
        UUID id,
        @NotNull UUID pedidoId,
        @NotNull String metodo,
        @NotNull @Positive BigDecimal monto) {

    MetodoPago metodoPago() {
        try {
            return MetodoPago.desde(metodo);
        } catch (IllegalArgumentException e) {
            throw ReglaDeNegocioException.invalido("METODO_INVALIDO", "Metodo de pago desconocido: " + metodo);
        }
    }
}
