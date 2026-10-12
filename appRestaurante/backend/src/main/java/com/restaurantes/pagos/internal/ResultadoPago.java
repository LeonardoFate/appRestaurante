package com.restaurantes.pagos.internal;

import com.restaurantes.pedidos.domain.EstadoPago;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado de registrar un pago.
 *
 * @param creadoAhora {@code false} si es la repeticion idempotente de un pago ya registrado
 */
record ResultadoPago(UUID pagoId, UUID pedidoId, BigDecimal monto, EstadoPago estadoPago, BigDecimal saldo,
                     boolean creadoAhora) {
}
