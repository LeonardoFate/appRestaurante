package com.restaurantes.pagos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PoliticaDeCobroTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void calculaElSaldo() {
        assertEquals(0, d("13.00").compareTo(PoliticaDeCobro.saldo(d("23.00"), d("10.00"))));
    }

    @Test
    void aceptaUnMontoIgualAlSaldoYMenor() {
        assertDoesNotThrow(() -> PoliticaDeCobro.validarMonto(d("13.00"), d("13.00")));
        assertDoesNotThrow(() -> PoliticaDeCobro.validarMonto(d("0.01"), d("13.00")));
    }

    @Test
    void rechazaMontoQueExcedeElSaldo() {
        ReglaDeNegocioException e = assertThrows(ReglaDeNegocioException.class,
                () -> PoliticaDeCobro.validarMonto(d("13.01"), d("13.00")));
        assertEquals("MONTO_EXCEDE_SALDO", e.codigo());
    }

    @Test
    void rechazaMontoCeroONegativo() {
        assertEquals("MONTO_INVALIDO", assertThrows(ReglaDeNegocioException.class,
                () -> PoliticaDeCobro.validarMonto(d("0"), d("10.00"))).codigo());
        assertEquals("MONTO_INVALIDO", assertThrows(ReglaDeNegocioException.class,
                () -> PoliticaDeCobro.validarMonto(d("-5"), d("10.00"))).codigo());
    }

    @Test
    void rechazaCobrarUnPedidoSinSaldo() {
        ReglaDeNegocioException e = assertThrows(ReglaDeNegocioException.class,
                () -> PoliticaDeCobro.validarMonto(d("1.00"), d("0.00")));
        assertEquals("PEDIDO_YA_PAGADO", e.codigo());
    }

    @Test
    void determinaElEstadoTrasCobrar() {
        assertEquals(EstadoPago.SIN_PAGO, PoliticaDeCobro.estadoTras(d("23.00"), d("0.00")));
        assertEquals(EstadoPago.PARCIAL, PoliticaDeCobro.estadoTras(d("23.00"), d("10.00")));
        assertEquals(EstadoPago.PAGADO, PoliticaDeCobro.estadoTras(d("23.00"), d("23.00")));
    }

    @Test
    void laPasarelaNoEstaSoportadaTodavia() {
        assertEquals(false, MetodoPago.PASARELA.soportado());
        assertEquals(true, MetodoPago.EFECTIVO.soportado());
    }
}
