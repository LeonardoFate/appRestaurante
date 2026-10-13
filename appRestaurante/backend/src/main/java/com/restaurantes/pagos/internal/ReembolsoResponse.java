package com.restaurantes.pagos.internal;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Respuesta al registrar un reembolso.
 *
 * @param estadoPago  estado de cobro del pedido ({@code reembolsado_parcial} o {@code reembolsado})
 * @param estadoPedido estado del pedido ({@code devuelto_parcial}/{@code devuelto_total} si ya se habia entregado)
 * @param cobradoNeto lo que sigue cobrado del pedido tras el reembolso
 */
public record ReembolsoResponse(UUID reembolsoId, UUID pagoId, UUID pedidoId, BigDecimal monto, String estadoPago,
                                String estadoPedido, BigDecimal cobradoNeto) {

    static ReembolsoResponse de(ResultadoReembolso r) {
        return new ReembolsoResponse(r.reembolsoId(), r.pagoId(), r.pedidoId(), r.monto(), r.estadoPagoDelPedido().valor(),
                r.estadoPedido(), r.cobradoNeto());
    }
}
