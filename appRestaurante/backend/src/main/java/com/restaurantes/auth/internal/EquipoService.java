package com.restaurantes.auth.internal;

import com.restaurantes.auth.api.Equipo;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@ApplicationScoped
class EquipoService implements Equipo {

    @Inject
    TenantTransaction tx;

    @Override
    public boolean tieneRolActivo(UUID usuarioId, String rol, UUID sucursalId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                select 1 from membresias
                 where usuario_id = ? and rol = ? and estado = 'activa' and (sucursal_id is null or sucursal_id = ?)
                 limit 1
                """)) {
            ps.setObject(1, usuarioId);
            ps.setString(2, rol);
            ps.setObject(3, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo consultar la membresia", e);
        }
    }
}
