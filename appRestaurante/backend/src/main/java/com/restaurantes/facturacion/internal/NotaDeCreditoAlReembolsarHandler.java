package com.restaurantes.facturacion.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.restaurantes.shared.events.EventoDominio;
import com.restaurantes.shared.events.ManejadorDeEvento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Cuando se reembolsa dinero ({@code PagoReembolsado}), acredita la factura si la venta quedo devuelta por completo. Lo
 * invoca el relay del outbox. Es idempotente y no depende del orden respecto a {@code PedidoPagado}.
 */
@ApplicationScoped
class NotaDeCreditoAlReembolsarHandler implements ManejadorDeEvento {

    @Inject
    FacturacionService facturacion;

    @Override
    public String consumidor() {
        return "facturacion.nota-credito";
    }

    @Override
    public String tipoEvento() {
        return "PagoReembolsado";
    }

    @Override
    public void manejar(EventoDominio evento) {
        JsonNode payload = evento.payload();
        JsonNode motivo = payload.get("motivo");
        facturacion.reconciliarNotaDeCredito(UUID.fromString(payload.get("pedidoId").asText()),
                motivo == null || motivo.isNull() ? null : motivo.asText());
    }
}
