package com.restaurantes.pedidos.domain;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Calcula los totales de un pedido. Java puro y determinista: el mismo pedido da siempre los mismos
 * totales. El IVA se calcula por linea y se suma (cada linea redondeada a 2 decimales), que es como
 * quedara en el comprobante. VERIFICAR con la normativa del SRI si el redondeo debe hacerse por linea o por base total.
 */
public final class CalculadoraTotales {

    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    private CalculadoraTotales() {
    }

    public static Totales calcular(List<LineaPedido> lineas, BigDecimal propina) {
        return calcular(lineas, propina, null);
    }

    /** Igual, con costo de envio (delivery). El envio no lleva IVA en este modelo (supuesto fiscal sin verificar). */
    public static Totales calcular(List<LineaPedido> lineas, BigDecimal propina, BigDecimal costoEnvio) {
        if (lineas == null || lineas.isEmpty()) {
            throw ReglaDeNegocioException.invalido("PEDIDO_SIN_ITEMS", "El pedido debe tener al menos un item");
        }
        BigDecimal propinaFinal = propina == null ? CERO : propina.setScale(2, RoundingMode.HALF_UP);
        if (propinaFinal.signum() < 0) {
            throw ReglaDeNegocioException.invalido("PROPINA_NEGATIVA", "La propina no puede ser negativa");
        }

        BigDecimal subtotal = CERO;
        BigDecimal iva = CERO;
        for (LineaPedido linea : lineas) {
            if (linea.cantidad().signum() <= 0) {
                throw ReglaDeNegocioException.invalido("CANTIDAD_INVALIDA", "La cantidad de '" + linea.nombre() + "' debe ser mayor que cero");
            }
            subtotal = subtotal.add(linea.subtotal());
            iva = iva.add(linea.iva());
        }
        BigDecimal descuento = CERO;
        BigDecimal envio = costoEnvio == null ? CERO : costoEnvio.setScale(2, RoundingMode.HALF_UP);
        if (envio.signum() < 0) {
            throw ReglaDeNegocioException.invalido("ENVIO_NEGATIVO", "El costo de envio no puede ser negativo");
        }
        BigDecimal total = subtotal.subtract(descuento).add(iva).add(propinaFinal).add(envio);
        return new Totales(subtotal, descuento, iva, propinaFinal, envio, total);
    }
}
