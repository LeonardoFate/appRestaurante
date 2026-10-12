package com.restaurantes.pedidos.api;

import com.restaurantes.pedidos.domain.CanalPedido;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Orden de crear un pedido. El {@code id} lo genera el cliente (UUIDv7) para que reintentar la
 * misma orden sea idempotente. Puede ser null si el cliente es online y no lo envia.
 */
public record NuevoPedido(
        UUID id,
        CanalPedido canal,
        UUID mesaId,
        BigDecimal propina,
        List<NuevaLinea> lineas) {

    /** Linea solicitada: el precio NO lo envia el cliente; sale del menu. */
    public record NuevaLinea(UUID id, UUID productoId, BigDecimal cantidad, String nota) {
    }
}
