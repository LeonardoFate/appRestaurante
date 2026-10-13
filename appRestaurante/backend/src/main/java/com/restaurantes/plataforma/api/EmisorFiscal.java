package com.restaurantes.plataforma.api;

/**
 * Datos fiscales del emisor de una factura: el restaurante (RUC, razon social) y el establecimiento donde se emite
 * (codigos de establecimiento y punto de emision del SRI).
 *
 * @param ambiente {@code pruebas} o {@code produccion} (ambiente del SRI configurado para el restaurante)
 */
public record EmisorFiscal(
        String ruc,
        String razonSocial,
        String nombreComercial,
        String direccionEstablecimiento,
        String codEstablecimiento,
        String codPuntoEmision,
        String ambiente,
        boolean obligadoContabilidad) {
}
