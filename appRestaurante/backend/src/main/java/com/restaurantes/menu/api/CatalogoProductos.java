package com.restaurantes.menu.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Consulta del menu para vender. Es lo unico del modulo {@code menu} que otros modulos pueden usar.
 * Debe llamarse dentro de una unidad de trabajo de tenant ({@code TenantTransaction.withTenant}).
 */
public interface CatalogoProductos {

    /**
     * Devuelve los productos encontrados (del tenant actual) indexados por id. Los ids que no
     * existen, o pertenecen a otro tenant, simplemente no aparecen en el resultado.
     */
    Map<UUID, ProductoVenta> buscar(UUID sucursalId, Collection<UUID> productoIds);
}
