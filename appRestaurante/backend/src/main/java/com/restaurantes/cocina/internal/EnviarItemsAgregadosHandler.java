package com.restaurantes.cocina.internal;

import com.restaurantes.shared.events.EventoDominio;
import com.restaurantes.shared.events.ManejadorDeEvento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Cuando se agregan items a un pedido que cocina ya recibio ({@code ItemsAgregados}), los nuevos pasan a su estacion.
 * Reutiliza el envio de "lo pendiente del pedido": es idempotente, asi que una reentrega no repite nada.
 */
@ApplicationScoped
class EnviarItemsAgregadosHandler implements ManejadorDeEvento {

    @Inject
    CocinaService cocina;

    @Override
    public String consumidor() {
        return "cocina.enviar-items-agregados";
    }

    @Override
    public String tipoEvento() {
        return "ItemsAgregados";
    }

    @Override
    public void manejar(EventoDominio evento) {
        cocina.enviarAPedido(UUID.fromString(evento.payload().get("pedidoId").asText()));
    }
}
