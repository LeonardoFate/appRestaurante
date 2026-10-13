package com.restaurantes.shared.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Encolado de trabajos (ADR-015). Se llama dentro de la unidad de trabajo del cambio de negocio, de modo que el
 * trabajo existe si y solo si el cambio se confirmo (igual que el outbox).
 */
@ApplicationScoped
public class ColaDeJobs {

    public static final int MAX_INTENTOS_POR_DEFECTO = 5;

    @Inject
    TenantTransaction tx;

    @Inject
    ObjectMapper json;

    /**
     * Encola un trabajo.
     *
     * @param claveUnica idempotencia: si ya existe un job con la misma clave en esa cola y tenant no se crea otro
     *                   (puede ser null para no deduplicar)
     * @return el id del job creado, o vacio si ya existia uno con esa clave
     */
    public Optional<UUID> encolar(String cola, String tipo, Map<String, Object> payload, String claveUnica, int maxIntentos) {
        UUID id = UuidV7.next();
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into jobs(id, tenant_id, cola, tipo, payload, clave_unica, max_intentos)
                values (?, ?, ?, ?, ?::jsonb, ?, ?)
                on conflict (tenant_id, cola, clave_unica) where clave_unica is not null do nothing
                returning id
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, tx.tenantActual());
            ps.setString(3, cola);
            ps.setString(4, tipo);
            ps.setString(5, json.writeValueAsString(payload));
            ps.setString(6, claveUnica);
            ps.setInt(7, maxIntentos);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo encolar el trabajo " + tipo, e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payload del trabajo " + tipo + " no serializable", e);
        }
    }

    public Optional<UUID> encolar(String cola, String tipo, Map<String, Object> payload, String claveUnica) {
        return encolar(cola, tipo, payload, claveUnica, MAX_INTENTOS_POR_DEFECTO);
    }
}
