package com.restaurantes.delivery.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import org.junit.jupiter.api.Test;

class TipoIdentificacionTest {

    @Test
    void aceptaUnaCedulaConSuDigitoVerificador() {
        assertTrue(TipoIdentificacion.cedulaValida("1710034065"));
        assertDoesNotThrow(() -> TipoIdentificacion.validar("05", "1710034065"));
    }

    @Test
    void rechazaCualquierDigitoVerificadorEquivocado() {
        for (int d = 0; d <= 9; d++) {
            if (d != 5) {
                assertFalse(TipoIdentificacion.cedulaValida("171003406" + d), "verificador " + d);
            }
        }
    }

    @Test
    void rechazaProvinciaFueraDeRangoYTercerDigitoMayorOIgualASeis() {
        assertFalse(TipoIdentificacion.cedulaValida("2510034065"), "provincia 25");
        assertFalse(TipoIdentificacion.cedulaValida("0010034065"), "provincia 00");
        assertFalse(TipoIdentificacion.cedulaValida("1760034065"), "tercer digito 6");
        assertTrue(TipoIdentificacion.cedulaValida("3010034068"), "30 (ecuatorianos en el exterior) se admite como provincia");
    }

    @Test
    void rechazaLongitudYCaracteresInvalidos() {
        for (String malo : new String[] {"171003406", "17100340655", "17100340a5", "1710 34065"}) {
            assertEquals("CEDULA_INVALIDA", assertThrows(ReglaDeNegocioException.class,
                    () -> TipoIdentificacion.validar("05", malo)).codigo(), malo);
        }
        assertEquals("IDENTIFICACION_REQUERIDA", assertThrows(ReglaDeNegocioException.class,
                () -> TipoIdentificacion.validar("05", " ")).codigo());
    }

    @Test
    void rucDePersonaNaturalEsLaCedulaMasElEstablecimiento() {
        assertTrue(TipoIdentificacion.rucValido("1710034065001"));
        assertFalse(TipoIdentificacion.rucValido("1710034065000"), "establecimiento 000");
        assertFalse(TipoIdentificacion.rucValido("1710034061001"), "cedula con verificador malo");
    }

    @Test
    void rucDeSociedadPrivadaUsaModulo11() {
        assertTrue(TipoIdentificacion.rucValido("1790000001001"));
        assertFalse(TipoIdentificacion.rucValido("1790000002001"), "verificador malo");
        assertFalse(TipoIdentificacion.rucValido("1790000001000"), "establecimiento 000");
    }

    @Test
    void rucDeEntidadPublicaUsaModulo11ConSusPesos() {
        assertTrue(TipoIdentificacion.rucValido("1760001040001"));
        assertFalse(TipoIdentificacion.rucValido("1760001050001"), "verificador malo");
        assertFalse(TipoIdentificacion.rucValido("1760001040000"), "establecimiento 0000");
    }

    @Test
    void rucConTercerDigitoSieteOchoNoExiste() {
        assertFalse(TipoIdentificacion.rucValido("1770000001001"));
        assertFalse(TipoIdentificacion.rucValido("1780000001001"));
    }

    @Test
    void validarUsaLosCodigosDeNegocioYAceptaPasaporteSinFormato() {
        assertEquals("RUC_INVALIDO", assertThrows(ReglaDeNegocioException.class, () -> TipoIdentificacion.validar("04", "1790000002001")).codigo());
        assertEquals("CEDULA_INVALIDA", assertThrows(ReglaDeNegocioException.class, () -> TipoIdentificacion.validar("05", "1710034061")).codigo());
        assertEquals("IDENTIFICACION_TIPO_INVALIDO", assertThrows(ReglaDeNegocioException.class, () -> TipoIdentificacion.validar("07", "9999999999999")).codigo());
        assertEquals("IDENTIFICACION_REQUERIDA", assertThrows(ReglaDeNegocioException.class, () -> TipoIdentificacion.validar("06", " ")).codigo());
        assertDoesNotThrow(() -> TipoIdentificacion.validar("06", "AB123456"));
    }
}
