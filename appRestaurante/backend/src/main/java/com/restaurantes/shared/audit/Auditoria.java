package com.restaurantes.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;

/**
 * Registro de auditoria de acciones sensibles (reembolsos, anulaciones, cambios de permisos...). La tabla es de solo
 * insercion. Se escribe en la MISMA transaccion de la accion: si esta se revierte, el registro tambien, y si se confirma
 * siempre queda constancia de quien, cuando y que cambio.
 */
@ApplicationScoped
public class Auditoria {

    @Inject
    TenantTransaction tx;

    @Inject
    ObjectMapper json;

    /**
     * @param accion  por ejemplo {@code pago.reembolsar}
     * @param antes   estado previo relevante (puede ser null)
     * @param despues estado resultante (puede ser null)
     */
    public void registrar(UUID usuarioId, String accion, String entidad, UUID entidadId,
                          Map<String, Object> antes, Map<String, Object> despues) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into auditoria(tenant_id, usuario_id, accion, entidad, entidad_id, antes, despues, autorizado_por)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """)) {
            ps.setObject(1, tx.tenantActual());
            ps.setObject(2, usuarioId);
            ps.setString(3, accion);
            ps.setString(4, entidad);
            ps.setObject(5, entidadId);
            if (antes == null) {
                ps.setNull(6, Types.OTHER);
            } else {
                ps.setString(6, json.writeValueAsString(antes));
            }
            if (despues == null) {
                ps.setNull(7, Types.OTHER);
            } else {
                ps.setString(7, json.writeValueAsString(despues));
            }
            ps.setObject(8, usuarioId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo registrar la auditoria de " + accion, e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Datos de auditoria no serializables", e);
        }
    }
}
