package com.restaurantes.facturacion.domain;

/**
 * Comprador al que se factura (snapshot: no depende de que el cliente cambie despues).
 *
 * @param tipoIdentificacion codigo SRI: 04 RUC, 05 cedula, 06 pasaporte, 07 consumidor final
 */
public record Receptor(String tipoIdentificacion, String identificacion, String razonSocial, String email) {

    /**
     * Consumidor final. VERIFICAR con la normativa del SRI el monto maximo permitido para facturar a consumidor final
     * (por encima hay que identificar al comprador); hoy no se aplica ese limite.
     */
    public static Receptor consumidorFinal() {
        return new Receptor("07", "9999999999999", "CONSUMIDOR FINAL", null);
    }
}
