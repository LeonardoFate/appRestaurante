package com.restaurantes.salon.internal;

import com.restaurantes.salon.api.CatalogoMesas;
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
class CatalogoMesasJdbc implements CatalogoMesas {

    @Inject
    TenantTransaction tx;

    @Override
    public Map<UUID, String> codigos(Collection<UUID> mesaIds) {
        Map<UUID, String> resultado = new HashMap<>();
        if (mesaIds.isEmpty()) {
            return resultado;
        }
        Connection c = tx.connection();
        try {
            Array ids = c.createArrayOf("uuid", mesaIds.toArray());
            try (PreparedStatement ps = c.prepareStatement("select id, codigo from mesas where id = any (?)")) {
                ps.setArray(1, ids);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        resultado.put(rs.getObject("id", UUID.class), rs.getString("codigo"));
                    }
                }
            } finally {
                ids.free();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron consultar las mesas", e);
        }
        return resultado;
    }
}
