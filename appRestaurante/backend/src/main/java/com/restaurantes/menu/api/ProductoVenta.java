package com.restaurantes.menu.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Datos de un producto tal como se vende en una sucursal: precio efectivo (el de la sucursal si lo
 * tiene, o el base), tarifa de IVA vigente y estacion de cocina. Los pedidos copian estos datos
 * como "snapshot" para que cambiar el menu no altere ventas pasadas.
 *
 * @param precioUnitario precio ANTES de IVA (ver supuesto en la documentacion del modelo de datos)
 */
public record ProductoVenta(
        UUID id,
        String nombre,
        BigDecimal precioUnitario,
        BigDecimal porcentajeIva,
        String codigoIvaSri,
        UUID estacionId,
        boolean disponible,
        boolean activo) {
}
