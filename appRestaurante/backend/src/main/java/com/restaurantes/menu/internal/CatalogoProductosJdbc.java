package com.restaurantes.menu.internal;

import com.restaurantes.menu.api.CatalogoProductos;
import com.restaurantes.menu.api.ProductoVenta;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CatalogoProductosJdbc implements CatalogoProductos {

    private static final String SQL = """
            select p.id, p.nombre,
                   coalesce(ps.precio, p.precio_base) as precio,
                   t.porcentaje, t.codigo_sri,
                   ps.estacion_id,
                   coalesce(ps.disponible, true) as disponible,
                   p.activo
              from productos p
              join tarifas_iva t on t.id = p.tarifa_iva_id
              left join productos_sucursal ps on ps.producto_id = p.id and ps.sucursal_id = ?
             where p.id = any (?)
            """;

    @Inject
    TenantTransaction tx;

    @Override
    public Map<UUID, ProductoVenta> buscar(UUID sucursalId, Collection<UUID> productoIds) {
        Map<UUID, ProductoVenta> resultado = new HashMap<>();
        if (productoIds.isEmpty()) {
            return resultado;
        }
        Connection c = tx.connection();
        try {
            Array ids = c.createArrayOf("uuid", productoIds.toArray());
            try (PreparedStatement ps = c.prepareStatement(SQL)) {
                ps.setObject(1, sucursalId);
                ps.setArray(2, ids);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = rs.getObject("id", UUID.class);
                        resultado.put(id, new ProductoVenta(
                                id,
                                rs.getString("nombre"),
                                rs.getBigDecimal("precio"),
                                rs.getBigDecimal("porcentaje"),
                                rs.getString("codigo_sri"),
                                rs.getObject("estacion_id", UUID.class),
                                rs.getBoolean("disponible"),
                                rs.getBoolean("activo")));
                    }
                }
            } finally {
                ids.free();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo consultar el menu", e);
        }
        return resultado;
    }
}
