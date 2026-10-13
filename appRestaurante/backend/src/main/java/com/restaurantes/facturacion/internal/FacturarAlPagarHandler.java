package com.restaurantes.facturacion.internal;

import com.restaurantes.shared.events.EventoDominio;
import com.restaurantes.shared.events.ManejadorDeEvento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/** Cuando un pedido queda pagado ({@code PedidoPagado}), genera su factura. Lo invoca el relay del outbox. */
@ApplicationScoped
class FacturarAlPagarHandler implements ManejadorDeEvento {

    @Inject
    FacturacionService facturacion;

    @Override
    public String consumidor() {
        return "facturacion.generar-factura";
    }

    @Override
    public String tipoEvento() {
        return "PedidoPagado";
    }

    @Override
    public void manejar(EventoDominio evento) {
        UUID pedidoId = UUID.fromString(evento.payload().get("pedidoId").asText());
        facturacion.generarFactura(pedidoId);
    }
}
