package com.restaurantes.pagos.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Lo que otros modulos pueden pedirle a {@code pagos}. Se usa dentro de la unidad de trabajo del tenant. */
public interface PagosApi {

    /**
     * Registra en efectivo TODO lo que falta por cobrar del pedido (el repartidor cobra al entregar). Si ya esta pagado
     * no hace nada. Bloquea el pedido antes de calcular el saldo, igual que un cobro normal.
     *
     * @return lo cobrado ahora (cero si ya estaba pagado)
     */
    BigDecimal cobrarSaldoEnEfectivo(UUID pedidoId);
}
