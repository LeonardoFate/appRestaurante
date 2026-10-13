package com.restaurantes.auth.api;

import java.util.UUID;

/** Consultas sobre el equipo del restaurante que necesitan otros modulos. Se usa dentro de la unidad de trabajo del tenant. */
public interface Equipo {

    /**
     * @param sucursalId sucursal donde debe poder trabajar; una membresia sin sucursal vale para todas
     * @return {@code true} si el usuario tiene una membresia ACTIVA con ese rol en esa sucursal
     */
    boolean tieneRolActivo(UUID usuarioId, String rol, UUID sucursalId);
}
