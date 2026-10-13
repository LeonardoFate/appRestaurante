package com.restaurantes.pedidos.internal;

import com.restaurantes.pedidos.api.NuevoPedido;
import com.restaurantes.pedidos.internal.CrearPedidoRequest.Item;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Cuerpo de {@code POST /api/v1/pedidos/{id}/items}. Como al crear: el cliente NO envia precios, salen del menu. */
public record AgregarItemsRequest(@NotEmpty List<@Valid Item> items) {

    List<NuevoPedido.NuevaLinea> lineas() {
        return items.stream().map(i -> new NuevoPedido.NuevaLinea(i.id(), i.productoId(), i.cantidad(), i.nota())).toList();
    }
}
