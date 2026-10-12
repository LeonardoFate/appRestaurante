package com.restaurantes.pedidos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalculadoraTotalesTest {

    private static LineaPedido linea(String precio, String iva, String cantidad) {
        return new LineaPedido(UUID.randomUUID(), UUID.randomUUID(), "Plato", new BigDecimal(precio),
                new BigDecimal(iva), "4", new BigDecimal(cantidad), null, null);
    }

    private static void assertDinero(String esperado, BigDecimal real) {
        assertEquals(0, new BigDecimal(esperado).compareTo(real), "esperado " + esperado + " pero fue " + real);
        assertEquals(2, real.scale(), "todo importe debe tener 2 decimales");
    }

    @Test
    void calculaSubtotalIvaYTotal() {
        Totales t = CalculadoraTotales.calcular(List.of(linea("10.00", "15", "2")), null);
        assertDinero("20.00", t.subtotal());
        assertDinero("3.00", t.iva());
        assertDinero("0.00", t.propina());
        assertDinero("23.00", t.total());
    }

    @Test
    void mezclaTarifasDeIva() {
        Totales t = CalculadoraTotales.calcular(
                List.of(linea("10.00", "15", "1"), linea("4.00", "0", "2")), new BigDecimal("1.50"));
        assertDinero("18.00", t.subtotal());
        assertDinero("1.50", t.iva());
        assertDinero("1.50", t.propina());
        assertDinero("21.00", t.total());
    }

    @Test
    void redondeaCadaLineaAMitadHaciaArriba() {
        // 0.335 x 3 = 1.005 -> 1.01 ; IVA 15 % de 1.01 = 0.1515 -> 0.15
        Totales t = CalculadoraTotales.calcular(List.of(linea("0.335", "15", "3")), null);
        assertDinero("1.01", t.subtotal());
        assertDinero("0.15", t.iva());
        assertDinero("1.16", t.total());
    }

    @Test
    void cumpleLaIdentidadQueExigeLaBaseDeDatos() {
        Totales t = CalculadoraTotales.calcular(
                List.of(linea("7.33", "15", "3"), linea("2.17", "15", "1.5")), new BigDecimal("2.00"));
        BigDecimal esperado = t.subtotal().subtract(t.descuento()).add(t.iva()).add(t.propina()).add(t.costoEnvio());
        assertEquals(0, esperado.compareTo(t.total()));
    }

    @Test
    void esDeterminista() {
        List<LineaPedido> lineas = List.of(linea("3.99", "15", "7"), linea("12.50", "0", "2"));
        assertEquals(CalculadoraTotales.calcular(lineas, null), CalculadoraTotales.calcular(lineas, null));
    }

    @Test
    void rechazaPedidoSinItems() {
        ReglaDeNegocioException e = assertThrows(ReglaDeNegocioException.class, () -> CalculadoraTotales.calcular(List.of(), null));
        assertEquals("PEDIDO_SIN_ITEMS", e.codigo());
    }

    @Test
    void rechazaPropinaNegativa() {
        ReglaDeNegocioException e = assertThrows(ReglaDeNegocioException.class,
                () -> CalculadoraTotales.calcular(List.of(linea("1.00", "15", "1")), new BigDecimal("-1")));
        assertEquals("PROPINA_NEGATIVA", e.codigo());
    }

    @Test
    void rechazaCantidadCero() {
        ReglaDeNegocioException e = assertThrows(ReglaDeNegocioException.class,
                () -> CalculadoraTotales.calcular(List.of(linea("1.00", "15", "0")), null));
        assertEquals("CANTIDAD_INVALIDA", e.codigo());
    }
}
