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
        Integer numero,
        CanalPedido canal,
        String estado,
        EstadoPago estadoPago,
        Totales totales,
        List<Linea> lineas,
        boolean creadoAhora) {

    public record Linea(UUID id, UUID productoId, String nombre, BigDecimal precioUnitario,
                        BigDecimal porcentajeIva, BigDecimal cantidad, String nota) {
    }

    public PedidoVista comoRepeticion() {
        return new PedidoVista(id, numero, canal, estado, estadoPago, totales, lineas, false);
    }
}
