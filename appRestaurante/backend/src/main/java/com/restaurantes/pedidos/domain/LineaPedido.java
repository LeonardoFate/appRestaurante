package com.restaurantes.pedidos.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Linea de un pedido con los datos del producto copiados al vender (snapshot).
 *
 * @param precioUnitario precio antes de IVA
 * @param porcentajeIva  porcentaje de IVA (por ejemplo 15.00)
 */
public record LineaPedido(
        UUID id,
        UUID productoId,
        String nombre,
        BigDecimal precioUnitario,
        BigDecimal porcentajeIva,
        String codigoIvaSri,
        BigDecimal cantidad,
        String nota,
        UUID estacionId) {

    /** Base imponible de la linea: precio x cantidad, a 2 decimales (HALF_UP). */
    public BigDecimal subtotal() {
        return precioUnitario.multiply(cantidad).setScale(2, RoundingMode.HALF_UP);
    }

    /** IVA de la linea sobre su base, a 2 decimales (HALF_UP). */
    public BigDecimal iva() {
        return subtotal().multiply(porcentajeIva).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
