package com.restaurantes.delivery.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Contratos HTTP del reparto. */
final class EntregasDtos {

    private EntregasDtos() {
    }

    record AsignarRequest(@NotNull UUID repartidorId) {
    }

    record FallarRequest(@NotBlank @Size(max = 200) String motivo) {
    }

    record LiquidarRequest(@NotNull UUID repartidorId) {
    }

    record EntregaResponse(UUID pedidoId, Integer numeroPedido, String estado, UUID repartidorId, String estadoPedido,
                           String estadoPago, BigDecimal total, String cliente, String telefono, String direccion,
                           String referencia, BigDecimal efectivoPorCobrar, boolean efectivoLiquidado) {
        static EntregaResponse de(EntregaRepository.EntregaDetalle d) {
            return new EntregaResponse(d.pedidoId(), d.numeroPedido(), d.estado(), d.repartidorId(), d.estadoPedido(),
                    d.estadoPago(), d.total(), d.cliente(), d.telefono(), d.direccion(), d.referencia(),
                    d.efectivoPorCobrar(), d.liquidada());
        }
    }

    record LiquidacionResponse(UUID repartidorId, int entregas, BigDecimal efectivo) {
        static LiquidacionResponse de(EntregaRepository.PendienteDeLiquidar p) {
            return new LiquidacionResponse(p.repartidorId(), p.entregas(), p.efectivo());
        }
    }
}
