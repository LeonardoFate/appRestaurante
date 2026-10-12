package com.restaurantes.pagos.internal;

import java.math.BigDecimal;
import java.util.UUID;

/** Respuesta de la API al registrar un pago. */
public record PagoResponse(UUID pagoId, UUID pedidoId, BigDecimal monto, String estadoPago, BigDecimal saldo) {

    static PagoResponse de(ResultadoPago r) {
        return new PagoResponse(r.pagoId(), r.pedidoId(), r.monto(), r.estadoPago().valor(), r.saldo());
    }
}
