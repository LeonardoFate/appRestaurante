package com.restaurantes.pagos.domain;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.math.BigDecimal;
import java.util.Set;

/** Reglas puras de reembolso: cuanto se puede devolver de un pago y como queda ese pago. */
public final class PoliticaDeReembolso {

    /** Estados de un pago de los que se puede devolver dinero. */
    private static final Set<String> REEMBOLSABLES = Set.of("capturado", "reembolsado_parcial");

    private PoliticaDeReembolso() {
    }

    /** Lo que aun se puede devolver de un pago: su monto menos lo ya reembolsado. */
    public static BigDecimal reembolsable(BigDecimal montoDelPago, BigDecimal yaReembolsado) {
        return montoDelPago.subtract(yaReembolsado);
    }

    /** Un pago pendiente, rechazado o ya reembolsado del todo no admite reembolsos. */
    public static void validarEstadoDelPago(String estadoDelPago) {
        if (!REEMBOLSABLES.contains(estadoDelPago)) {
            throw ReglaDeNegocioException.conflicto("PAGO_NO_REEMBOLSABLE",
                    "Un pago '" + estadoDelPago + "' no admite reembolsos");
        }
    }

    public static void validar(BigDecimal monto, BigDecimal reembolsable, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw ReglaDeNegocioException.invalido("MOTIVO_REQUERIDO", "Indique el motivo del reembolso");
        }
        if (monto == null || monto.signum() <= 0) {
            throw ReglaDeNegocioException.invalido("MONTO_INVALIDO", "El monto debe ser mayor que cero");
        }
        if (monto.compareTo(reembolsable) > 0) {
            throw ReglaDeNegocioException.conflicto("MONTO_EXCEDE_REEMBOLSABLE",
                    "El monto (" + monto.toPlainString() + ") excede lo que se puede reembolsar de este pago ("
                            + reembolsable.toPlainString() + ")");
        }
    }

    /** Estado en que queda el pago tras reembolsar {@code monto} de lo {@code reembolsable}. */
    public static String estadoDelPagoTras(BigDecimal reembolsable, BigDecimal monto) {
        return monto.compareTo(reembolsable) >= 0 ? "reembolsado" : "reembolsado_parcial";
    }
}
