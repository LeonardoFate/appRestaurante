package com.restaurantes.plataforma.api;

import java.util.UUID;

/**
 * Lo unico del modulo {@code plataforma} que otros modulos pueden usar: los datos fiscales de un establecimiento.
 * Debe llamarse dentro de una unidad de trabajo de tenant.
 */
public interface DatosFiscales {

    /**
     * @throws com.restaurantes.shared.errors.ReglaDeNegocioException si la sucursal no existe en el restaurante actual
     */
    EmisorFiscal emisorDe(UUID sucursalId);
}
