package com.restaurantes.cocina.internal;

import com.restaurantes.shared.events.EventoDominio;
import com.restaurantes.shared.events.ManejadorDeEvento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/** Cuando se confirma un pedido ({@code PedidoConfirmado}), sus items pasan a cocina. Lo invoca el relay del outbox. */
@ApplicationScoped
class EnviarACocinaHandler implements ManejadorDeEvento {

    @Inject
    CocinaService cocina;

    @Override
    public String consumidor() {
        return "cocina.enviar-items";
    }

    @Override
    public String tipoEvento() {
        return "PedidoConfirmado";
    }

    @Override
    public void manejar(EventoDominio evento) {
        cocina.enviarAPedido(UUID.fromString(evento.payload().get("pedidoId").asText()));
    }
}
