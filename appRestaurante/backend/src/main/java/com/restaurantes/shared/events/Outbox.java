package com.restaurantes.shared.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/**
 * Publicacion de eventos de dominio mediante el outbox transaccional (ADR-003).
 *
 * <p>El evento se escribe en la MISMA transaccion que el cambio de negocio: o se guardan ambos o
 * ninguno. Un relay lo publica despues (entrega "al menos una vez"; los consumidores deduplican).
 */
@ApplicationScoped
public class Outbox {

    @Inject
    TenantTransaction tx;

    @Inject
    ObjectMapper json;

    /** Escribe el evento en el outbox de la transaccion actual y devuelve su {@code event_id}. */
    public UUID emitir(String tipo, String agregadoTipo, UUID agregadoId, Map<String, Object> payload) {
        try (PreparedStatement ps = tx.connection().prepareStatement("select app.emitir_evento(?, ?, ?, ?::jsonb)")) {
            ps.setString(1, tipo);
            ps.setString(2, agregadoTipo);
            ps.setObject(3, agregadoId);
            ps.setString(4, json.writeValueAsString(payload));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo escribir el evento " + tipo + " en el outbox", e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payload del evento " + tipo + " no serializable", e);
        }
    }
}
