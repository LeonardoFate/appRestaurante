package com.restaurantes.pedidos.internal;

import com.restaurantes.pedidos.api.PedidoVista;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Respuesta de la API para un pedido. */
public record PedidoResponse(
        UUID id,
        Integer numero,
        String canal,
        String estado,
        String estadoPago,
        BigDecimal subtotal,
        BigDecimal descuento,
        BigDecimal iva,
        BigDecimal propina,
        BigDecimal costoEnvio,
        BigDecimal total,
        List<Item> items) {

    public record Item(UUID id, UUID productoId, String nombre, BigDecimal precioUnitario,
                       BigDecimal porcentajeIva, BigDecimal cantidad, String nota) {
    }

    static PedidoResponse de(PedidoVista v) {
        return new PedidoResponse(v.id(), v.numero(), v.canal().valor(), v.estado(), v.estadoPago().valor(),
                v.totales().subtotal(), v.totales().descuento(), v.totales().iva(), v.totales().propina(),
                v.totales().costoEnvio(), v.totales().total(),
                v.lineas().stream()
                        .map(l -> new Item(l.id(), l.productoId(), l.nombre(), l.precioUnitario(), l.porcentajeIva(),
                                l.cantidad(), l.nota()))
                        .toList());
    }
}
