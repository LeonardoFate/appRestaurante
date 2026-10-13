package com.restaurantes.pagos.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PoliticaDeReembolsoTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static String codigo(Runnable r) {
        return assertThrows(ReglaDeNegocioException.class, r::run).codigo();
    }

    @Test
    void calculaLoReembolsable() {
        assertEquals(0, d("7.00").compareTo(PoliticaDeReembolso.reembolsable(d("10.00"), d("3.00"))));
    }

    @Test
    void soloSeReembolsanPagosCapturadosOParcialmenteReembolsados() {
        assertDoesNotThrow(() -> PoliticaDeReembolso.validarEstadoDelPago("capturado"));
        assertDoesNotThrow(() -> PoliticaDeReembolso.validarEstadoDelPago("reembolsado_parcial"));
        for (String estado : new String[] {"pendiente", "rechazado", "reembolsado", "anulado"}) {
            assertEquals("PAGO_NO_REEMBOLSABLE", codigo(() -> PoliticaDeReembolso.validarEstadoDelPago(estado)), estado);
        }
    }

    @Test
    void validaMotivoMontoYTope() {
        assertDoesNotThrow(() -> PoliticaDeReembolso.validar(d("7.00"), d("7.00"), "error"));
        assertDoesNotThrow(() -> PoliticaDeReembolso.validar(d("0.01"), d("7.00"), "error"));
        assertEquals("MOTIVO_REQUERIDO", codigo(() -> PoliticaDeReembolso.validar(d("1"), d("7"), " ")));
        assertEquals("MOTIVO_REQUERIDO", codigo(() -> PoliticaDeReembolso.validar(d("1"), d("7"), null)));
        assertEquals("MONTO_INVALIDO", codigo(() -> PoliticaDeReembolso.validar(d("0"), d("7"), "x")));
        assertEquals("MONTO_INVALIDO", codigo(() -> PoliticaDeReembolso.validar(d("-1"), d("7"), "x")));
        assertEquals("MONTO_EXCEDE_REEMBOLSABLE", codigo(() -> PoliticaDeReembolso.validar(d("7.01"), d("7.00"), "x")));
    }

    @Test
    void elPagoQuedaReembolsadoSoloSiSeDevuelveTodoLoReembolsable() {
        assertEquals("reembolsado", PoliticaDeReembolso.estadoDelPagoTras(d("7.00"), d("7.00")));
        assertEquals("reembolsado_parcial", PoliticaDeReembolso.estadoDelPagoTras(d("7.00"), d("6.99")));
    }

    @Test
    void elEstadoDeCobroUsaLoCobradoNeto() {
        // neto 0 tras reembolsar => reembolsado; neto parcial => reembolsado_parcial; neto completo => pagado
        assertEquals(EstadoPago.REEMBOLSADO, PoliticaDeCobro.estadoTras(d("23.00"), d("0.00"), true));
        assertEquals(EstadoPago.REEMBOLSADO_PARCIAL, PoliticaDeCobro.estadoTras(d("23.00"), d("10.00"), true));
        assertEquals(EstadoPago.PARCIAL, PoliticaDeCobro.estadoTras(d("23.00"), d("10.00"), false));
        assertEquals(EstadoPago.PAGADO, PoliticaDeCobro.estadoTras(d("23.00"), d("23.00"), true), "re-cobro del neto completo");
        assertEquals(EstadoPago.SIN_PAGO, PoliticaDeCobro.estadoTras(d("23.00"), d("0.00"), false));
    }

    @Test
    void sinDineroCobradoEsSinPagoOReembolsado() {
        for (EstadoPago e : EstadoPago.values()) {
            assertEquals(e == EstadoPago.SIN_PAGO || e == EstadoPago.REEMBOLSADO, e.sinDineroCobrado(), e.name());
        }
    }
}
