package com.restaurantes.pedidos.api;

import com.restaurantes.pedidos.domain.CanalPedido;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Orden de crear un pedido. El {@code id} lo genera el cliente (UUIDv7) para que reintentar la
 * misma orden sea idempotente. Puede ser null si el cliente es online y no lo envia.
 *
 * @param envio datos de entrega; solo para el canal delivery (null en los demas)
 */
public record NuevoPedido(
        UUID id,
        CanalPedido canal,
        UUID mesaId,
        BigDecimal propina,
        List<NuevaLinea> lineas,
        Envio envio) {

    /** Constructor sin envio: salon y para llevar. */
    public NuevoPedido(UUID id, CanalPedido canal, UUID mesaId, BigDecimal propina, List<NuevaLinea> lineas) {
        this(id, canal, mesaId, propina, lineas, null);
    }

    /** Linea solicitada: el precio NO lo envia el cliente; sale del menu. */
    public record NuevaLinea(UUID id, UUID productoId, BigDecimal cantidad, String nota) {
    }

    /**
     * Entrega a domicilio, ya resuelta por el modulo delivery (zona, costo y minimo salen de la zona, nunca del cliente).
     *
     * @param momentoPago {@code anticipado}, {@code al_final} o {@code contra_entrega}
     */
    public record Envio(UUID clienteId, UUID direccionId, BigDecimal costoEnvio, BigDecimal pedidoMinimo, String momentoPago) {
    }
}
