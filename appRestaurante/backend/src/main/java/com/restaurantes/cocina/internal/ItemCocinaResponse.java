package com.restaurantes.cocina.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un item tal como lo muestra la pantalla de cocina (KDS).
 *
 * @param mesa            codigo visible de la mesa ("M12"), o null si el pedido no es de salon
 * @param segundosEnEspera desde que se envio a cocina; la pantalla ordena los mas antiguos primero
 */
public record ItemCocinaResponse(
        UUID itemId,
        UUID pedidoId,
        Integer numeroPedido,
        String canal,
        String mesa,
        String nombre,
        BigDecimal cantidad,
        String nota,
        String estado,
        UUID estacionId,
        String tiempoServicio,
        OffsetDateTime enviadoEn,
        long segundosEnEspera) {
}
