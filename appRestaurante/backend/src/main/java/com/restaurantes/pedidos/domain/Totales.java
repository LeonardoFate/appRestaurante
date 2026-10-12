package com.restaurantes.pedidos.domain;

import java.math.BigDecimal;

/**
 * Totales de un pedido. Cumple la misma identidad que exige la base de datos:
 * {@code total = subtotal - descuento + iva + propina + costoEnvio}. La propina no forma parte de la base imponible.
 */
public record Totales(
        BigDecimal subtotal,
        BigDecimal descuento,
        BigDecimal iva,
        BigDecimal propina,
        BigDecimal costoEnvio,
        BigDecimal total) {
}
