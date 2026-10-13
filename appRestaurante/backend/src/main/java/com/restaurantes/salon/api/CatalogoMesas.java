package com.restaurantes.salon.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Consulta de mesas para mostrar. Es lo unico del modulo {@code salon} que otros modulos pueden usar.
 * Debe llamarse dentro de una unidad de trabajo de tenant.
 */
public interface CatalogoMesas {

    /** Codigo visible ("M12") de cada mesa encontrada del tenant actual, indexado por id de mesa. */
    Map<UUID, String> codigos(Collection<UUID> mesaIds);
}
