package com.restaurantes.auth.internal;

import com.restaurantes.auth.domain.Membresia;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import io.agroal.api.AgroalDataSource;
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

/**
 * Acceso a las funciones {@code SECURITY DEFINER} de login. Es la UNICA excepcion al uso de
 * {@code TenantTransaction}: el login ocurre ANTES de saber a que restaurante pertenece el usuario.
 *
 * <p>Es seguro porque solo llama a funciones del esquema {@code app} que devuelven lo minimo necesario; si
 * este codigo intentara leer cualquier otra tabla, RLS fallaria cerrado (no hay tenant en el contexto).
 * ArchUnit impide que otro paquete use el pool directamente.
 *
 * <p>Cada llamada usa su propia conexion en autocommit y la devuelve al pool de inmediato; nunca debe
 * invocarse dentro de una unidad de trabajo de tenant.
 */
@ApplicationScoped
class UsuariosLoginRepository {

    @Inject
    AgroalDataSource dataSource;

    Optional<UsuarioLogin> buscarLogin(String email) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select id, email, password_hash, activo, bloqueado from app.buscar_usuario_login(?)")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UsuarioLogin(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("password_hash"), rs.getBoolean("activo"), rs.getBoolean("bloqueado")));
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo consultar el usuario", e);
        }
    }

    List<Membresia> membresias(UUID usuarioId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select tenant_id, sucursal_id, rol from app.membresias_de_usuario(?)")) {
            ps.setObject(1, usuarioId);
            List<Membresia> resultado = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    resultado.add(new Membresia(rs.getObject("tenant_id", UUID.class),
                            rs.getObject("sucursal_id", UUID.class), rs.getString("rol")));
                }
            }
            return resultado;
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron consultar las membresias", e);
        }
    }

    void registrarFallido(UUID usuarioId, int maxIntentos, int bloqueoMinutos) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select app.registrar_login_fallido(?, ?, ?)")) {
            ps.setObject(1, usuarioId);
            ps.setInt(2, maxIntentos);
            ps.setInt(3, bloqueoMinutos);
            ps.execute();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo registrar el intento fallido", e);
        }
    }

    void registrarExitoso(UUID usuarioId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select app.registrar_login_exitoso(?)")) {
            ps.setObject(1, usuarioId);
            ps.execute();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo registrar el login", e);
        }
    }
}
