package com.restaurantes.plataforma.internal;

import com.restaurantes.plataforma.api.DatosFiscales;
import com.restaurantes.plataforma.api.EmisorFiscal;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@ApplicationScoped
class DatosFiscalesJdbc implements DatosFiscales {

    @Inject
    TenantTransaction tx;

    @Override
    public EmisorFiscal emisorDe(UUID sucursalId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                select t.ruc, t.razon_social, t.nombre_comercial, t.ambiente_sri, t.obligado_contabilidad,
                       s.direccion, s.cod_establecimiento, s.cod_punto_emision
                  from sucursales s
                  join tenants t on t.id = s.tenant_id
                 where s.id = ?
                """)) {
            ps.setObject(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ReglaDeNegocioException.noEncontrado("SUCURSAL_NO_ENCONTRADA", "La sucursal no existe");
                }
                return new EmisorFiscal(rs.getString("ruc"), rs.getString("razon_social"), rs.getString("nombre_comercial"),
                        rs.getString("direccion"), rs.getString("cod_establecimiento"), rs.getString("cod_punto_emision"),
                        rs.getString("ambiente_sri"), rs.getBoolean("obligado_contabilidad"));
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron leer los datos fiscales", e);
        }
    }
}
