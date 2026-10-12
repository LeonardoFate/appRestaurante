package com.restaurantes.auth.internal;

import com.restaurantes.auth.domain.Membresia;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL de los refresh tokens y de las comprobaciones del tenant. Usa la unidad de trabajo del tenant del token. */
@ApplicationScoped
class RefreshTokenRepository {

    /** Estado de un refresh token. Los tiempos se evaluan con el reloj de la base de datos. */
    record Fila(UUID id, UUID familiaId, UUID usuarioId, UUID sucursalId, boolean usado, boolean revocado, boolean expirado) {
    }

    @Inject
    TenantTransaction tx;

    void insertar(UUID familiaId, UUID usuarioId, UUID sucursalId, String tokenHash, int dias) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into refresh_tokens(tenant_id, familia_id, usuario_id, sucursal_id, token_hash, expira_en)
                values (?, ?, ?, ?, ?, now() + make_interval(days => ?))
                """)) {
            ps.setObject(1, tx.tenantActual());
            ps.setObject(2, familiaId);
            ps.setObject(3, usuarioId);
            ps.setObject(4, sucursalId);
            ps.setString(5, tokenHash);
            ps.setInt(6, dias);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo guardar el refresh token", e);
        }
    }

    /** Busca el token y lo BLOQUEA: dos renovaciones simultaneas del mismo token se serializan. */
    Optional<Fila> buscarParaRotar(String tokenHash) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                select id, familia_id, usuario_id, sucursal_id,
                       usado_en is not null as usado, revocado_en is not null as revocado, expira_en <= now() as expirado
                  from refresh_tokens where token_hash = ? for update
                """)) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Fila(rs.getObject("id", UUID.class), rs.getObject("familia_id", UUID.class),
                        rs.getObject("usuario_id", UUID.class), rs.getObject("sucursal_id", UUID.class),
                        rs.getBoolean("usado"), rs.getBoolean("revocado"), rs.getBoolean("expirado")));
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el refresh token", e);
        }
    }

    void marcarUsado(UUID id) {
        try (PreparedStatement ps = tx.connection().prepareStatement("update refresh_tokens set usado_en = now() where id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo marcar el refresh token como usado", e);
        }
    }

    /** Revoca todos los tokens de la familia (cadena de renovaciones de una sesion). */
    int revocarFamilia(UUID familiaId) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "update refresh_tokens set revocado_en = now() where familia_id = ? and revocado_en is null")) {
            ps.setObject(1, familiaId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo revocar la sesion", e);
        }
    }

    boolean tenantActivo() {
        return existe("select 1 from tenants where id = ? and estado in ('activo', 'prueba')", tx.tenantActual());
    }

    boolean sucursalActiva(UUID sucursalId) {
        return existe("select 1 from sucursales where id = ? and activa", sucursalId);
    }

    /** Visible solo si el usuario tiene membresia en este restaurante (RLS de usuarios). */
    boolean usuarioActivo(UUID usuarioId) {
        return existe("select 1 from usuarios where id = ? and activo", usuarioId);
    }

    List<Membresia> membresiasActivas(UUID usuarioId) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select sucursal_id, rol from membresias where usuario_id = ? and estado = 'activa'")) {
            ps.setObject(1, usuarioId);
            List<Membresia> resultado = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    resultado.add(new Membresia(tx.tenantActual(), rs.getObject("sucursal_id", UUID.class), rs.getString("rol")));
                }
            }
            return resultado;
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron leer las membresias", e);
        }
    }

    private boolean existe(String sql, UUID parametro) {
        Connection c = tx.connection();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, parametro);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo comprobar el acceso", e);
        }
    }
}
