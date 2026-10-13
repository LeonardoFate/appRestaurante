package com.restaurantes.delivery.internal;

import com.restaurantes.shared.events.EventoDominio;
import com.restaurantes.shared.events.ManejadorDeEvento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Si se cancela un pedido a domicilio ({@code PedidoCancelado}) con su entrega aun en curso, la entrega queda
 * {@code fallido} con el motivo. Lo invoca el relay del outbox; es idempotente y no toca entregas ya entregadas.
 */
@ApplicationScoped
class FallarEntregaAlCancelarHandler implements ManejadorDeEvento {

    @Inject
    EntregaRepository repo;

    @Override
    public String consumidor() {
        return "delivery.fallar-entrega";
    }

    @Override
    public String tipoEvento() {
        return "PedidoCancelado";
    }

    @Override
    public void manejar(EventoDominio evento) {
        UUID pedidoId = UUID.fromString(evento.payload().get("pedidoId").asText());
        var motivo = evento.payload().get("motivo");
        repo.fallarSiEnCurso(pedidoId, "Pedido cancelado" + (motivo == null || motivo.isNull() ? "" : ": " + motivo.asText()));
    }
}
