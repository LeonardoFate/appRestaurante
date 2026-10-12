package com.restaurantes.pagos.domain;

import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.math.BigDecimal;

/** Reglas puras de cobro: cuanto se puede cobrar y en que estado queda el pedido. */
public final class PoliticaDeCobro {

    private PoliticaDeCobro() {
    }

    /** Saldo pendiente: total del pedido menos lo ya cobrado. */
    public static BigDecimal saldo(BigDecimal total, BigDecimal yaCobrado) {
        return total.subtract(yaCobrado);
    }

    /** Valida que {@code monto} sea positivo y no exceda el saldo. */
    public static void validarMonto(BigDecimal monto, BigDecimal saldo) {
        if (monto == null || monto.signum() <= 0) {
            throw ReglaDeNegocioException.invalido("MONTO_INVALIDO", "El monto debe ser mayor que cero");
        }
        if (saldo.signum() <= 0) {
            throw ReglaDeNegocioException.conflicto("PEDIDO_YA_PAGADO", "El pedido no tiene saldo pendiente");
        }
        if (monto.compareTo(saldo) > 0) {
            throw ReglaDeNegocioException.conflicto("MONTO_EXCEDE_SALDO",
                    "El monto (" + monto.toPlainString() + ") excede el saldo pendiente (" + saldo.toPlainString() + ")");
        }
    }

    /** Estado de pago del pedido tras cobrar {@code cobradoTotal} de un {@code total}. */
    public static EstadoPago estadoTras(BigDecimal total, BigDecimal cobradoTotal) {
        int comparacion = cobradoTotal.compareTo(total);
        if (comparacion >= 0) {
            return EstadoPago.PAGADO;
        }
        return cobradoTotal.signum() > 0 ? EstadoPago.PARCIAL : EstadoPago.SIN_PAGO;
    }
}
