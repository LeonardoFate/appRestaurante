package com.restaurantes.cocina.internal;

import java.util.UUID;

/** Estacion de cocina (parrilla, bar, fria...) de la sucursal del token. */
public record EstacionResponse(UUID id, String nombre) {
}
