package com.restaurantes.delivery.internal;

import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.CanalPedido;
import com.restaurantes.pedidos.domain.EstadoPedido;
import com.restaurantes.shared.events.EventoDominio;
import com.restaurantes.shared.events.ManejadorDeEvento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;

/**
 * Cuando un pedido a domicilio queda listo en cocina ({@code PedidoListo}), nace su entrega pendiente de asignar. Lo
 * invoca el relay del outbox. Es idempotente y no crea entregas para pedidos que no son delivery o ya se cancelaron.
 */
@ApplicationScoped
class CrearEntregaAlQuedarListoHandler implements ManejadorDeEvento {

    @Inject
    PedidosApi pedidos;

    @Inject
    EntregaRepository repo;

    @Override
    public String consumidor() {
        return "delivery.crear-entrega";
    }

    @Override
    public String tipoEvento() {
        return "PedidoListo";
    }

    @Override
    public void manejar(EventoDominio evento) {
        UUID pedidoId = UUID.fromString(evento.payload().get("pedidoId").asText());
        Optional<PedidoVista> pedido = pedidos.buscar(pedidoId);
        if (pedido.isEmpty() || pedido.get().canal() != CanalPedido.DELIVERY_PROPIO
                || EstadoPedido.desde(pedido.get().estado()) == EstadoPedido.CANCELADO) {
            return;
        }
        repo.crearSiNoExiste(pedidoId);
    }
}
