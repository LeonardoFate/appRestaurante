package com.restaurantes.pagos.domain;

import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.math.BigDecimal;

/** Reglas puras de cobro: cuanto se puede cobrar y en que estado queda el pedido. */
public final class PoliticaDeCobro {

    private PoliticaDeCobro() {
    }

    /** Saldo pendiente: total del pedido menos lo ya cobrado (NETO: lo cobrado menos lo reembolsado). */
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

    /** Estado de pago del pedido tras cobrar {@code cobradoTotal} de un {@code total}, cuando no hubo reembolsos. */
    public static EstadoPago estadoTras(BigDecimal total, BigDecimal cobradoTotal) {
        return estadoTras(total, cobradoTotal, false);
    }

    /**
     * Estado de pago del pedido segun lo cobrado NETO (cobrado menos reembolsado) y si hubo reembolsos.
     * Sin nada cobrado y con reembolsos es {@code reembolsado} (se devolvio todo); con reembolsos y algo aun cobrado,
     * {@code reembolsado_parcial}, hasta que se vuelva a cobrar el total.
     */
    public static EstadoPago estadoTras(BigDecimal total, BigDecimal cobradoNeto, boolean hayReembolsos) {
        if (cobradoNeto.signum() <= 0) {
            return hayReembolsos ? EstadoPago.REEMBOLSADO : EstadoPago.SIN_PAGO;
        }
        if (cobradoNeto.compareTo(total) >= 0) {
            return EstadoPago.PAGADO;
        }
        return hayReembolsos ? EstadoPago.REEMBOLSADO_PARCIAL : EstadoPago.PARCIAL;
    }
}
