package com.restaurantes.pedidos.api;

import com.restaurantes.pedidos.domain.CanalPedido;
import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.pedidos.domain.Totales;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Vista de lectura de un pedido para otros modulos y para la API.
 *
 * @param creadoAhora {@code true} si esta llamada lo creo; {@code false} si es la repeticion
 *                    idempotente de una orden ya procesada (la API responde 200 en vez de 201)
 */
public record PedidoVista(
        UUID id,
        UUID sucursalId,
        Integer numero,
        CanalPedido canal,
        String estado,
        EstadoPago estadoPago,
        String momentoPago,
        Totales totales,
        List<Linea> lineas,
        boolean creadoAhora) {

    /**
     * Linea del pedido con los importes ya calculados por el dominio (una sola fuente de verdad para el redondeo):
     * {@code subtotal} es la base imponible e {@code iva} el impuesto de esa linea.
     */
    public record Linea(UUID id, UUID productoId, String nombre, BigDecimal precioUnitario,
                        BigDecimal porcentajeIva, String codigoIvaSri, BigDecimal cantidad, String nota,
                        BigDecimal subtotal, BigDecimal iva) {
    }

    public PedidoVista comoRepeticion() {
        return new PedidoVista(id, sucursalId, numero, canal, estado, estadoPago, momentoPago, totales, lineas, false);
    }
}
