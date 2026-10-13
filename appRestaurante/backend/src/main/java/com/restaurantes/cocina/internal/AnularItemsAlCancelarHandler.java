package com.restaurantes.cocina.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.restaurantes.shared.events.EventoDominio;
import com.restaurantes.shared.events.ManejadorDeEvento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/** Cuando se cancela un pedido ({@code PedidoCancelado}), cocina anula sus items pendientes. Lo invoca el relay. */
@ApplicationScoped
class AnularItemsAlCancelarHandler implements ManejadorDeEvento {

    @Inject
    CocinaService cocina;

    @Override
    public String consumidor() {
        return "cocina.anular-items";
    }

    @Override
    public String tipoEvento() {
        return "PedidoCancelado";
    }

    @Override
    public void manejar(EventoDominio evento) {
        JsonNode payload = evento.payload();
        JsonNode usuario = payload.get("usuarioId");
        cocina.anularItemsDeCancelado(UUID.fromString(payload.get("pedidoId").asText()),
                payload.get("motivo").asText(),
                usuario == null || usuario.isNull() ? null : UUID.fromString(usuario.asText()));
    }
}
