package com.restaurantes.pagos.internal;

import com.restaurantes.pedidos.domain.EstadoPago;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado de registrar un reembolso.
 *
 * @param estadoPedido {@code estado} del pedido tras el reembolso ({@code devuelto_parcial}, {@code devuelto_total}...)
 * @param creadoAhora  {@code false} si es la repeticion idempotente de un reembolso ya registrado
 */
record ResultadoReembolso(UUID reembolsoId, UUID pagoId, UUID pedidoId, BigDecimal monto, EstadoPago estadoPagoDelPedido,
                          String estadoPedido, BigDecimal cobradoNeto, boolean creadoAhora) {
}
