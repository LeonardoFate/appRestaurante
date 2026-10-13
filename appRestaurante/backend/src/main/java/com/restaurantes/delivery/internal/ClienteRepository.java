package com.restaurantes.delivery.internal;

import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL explicito de clientes y direcciones de entrega. Usa la conexion de la unidad de trabajo. */
@ApplicationScoped
class ClienteRepository {

    record ClienteFila(UUID id, String nombre, String tipoIdentificacion, String identificacion, String telefono, String email) {
    }

    record DireccionFila(UUID id, UUID clienteId, String etiqueta, String direccion, String referencia) {
    }

    private static final String COLUMNAS = "id, nombre, tipo_identificacion, identificacion, telefono, email";

    @Inject
    TenantTransaction tx;

    /** Serializa la creacion de un mismo id (idempotencia) hasta el fin de la transaccion. */
    void bloquear(String clave) {
        try (PreparedStatement ps = tx.connection().prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            ps.setString(1, clave);
            ps.execute();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo bloquear la clave " + clave, e);
        }
    }

    Optional<ClienteFila> cliente(UUID id) {
        return uno("select " + COLUMNAS + " from clientes where id = ? and anonimizado_en is null", id);
    }

    Optional<ClienteFila> clientePorIdentificacion(String tipo, String identificacion) {
        return uno("select " + COLUMNAS + " from clientes where tipo_identificacion = ? and identificacion = ? "
                + "and anonimizado_en is null", tipo, identificacion);
    }

    List<ClienteFila> buscarPorTelefono(String telefono) {
        List<ClienteFila> filas = new ArrayList<>();
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select " + COLUMNAS + " from clientes where telefono = ? and anonimizado_en is null order by nombre limit 20")) {
            ps.setString(1, telefono);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    filas.add(fila(rs));
                }
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo buscar clientes", e);
        }
        return filas;
    }

    void insertarCliente(UUID id, String nombre, String tipo, String identificacion, String telefono, String email) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "insert into clientes(id, tenant_id, nombre, tipo_identificacion, identificacion, telefono, email) "
                        + "values (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tx.tenantActual());
            ps.setString(3, nombre);
            ps.setString(4, tipo);
            ps.setString(5, identificacion);
            ps.setString(6, telefono);
            ps.setString(7, email);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo guardar el cliente", e);
        }
    }

    Optional<DireccionFila> direccion(UUID id) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select id, cliente_id, etiqueta, direccion, referencia from direcciones_cliente where id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(filaDireccion(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer la direccion", e);
        }
    }

    List<DireccionFila> direcciones(UUID clienteId) {
        List<DireccionFila> filas = new ArrayList<>();
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select id, cliente_id, etiqueta, direccion, referencia from direcciones_cliente "
                        + "where cliente_id = ? order by creado_en")) {
            ps.setObject(1, clienteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    filas.add(filaDireccion(rs));
                }
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron leer las direcciones", e);
        }
        return filas;
    }

    void insertarDireccion(UUID id, UUID clienteId, String etiqueta, String direccion, String referencia) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "insert into direcciones_cliente(id, tenant_id, cliente_id, etiqueta, direccion, referencia) "
                        + "values (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tx.tenantActual());
            ps.setObject(3, clienteId);
            ps.setString(4, etiqueta);
            ps.setString(5, direccion);
            ps.setString(6, referencia);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo guardar la direccion", e);
        }
    }

    private Optional<ClienteFila> uno(String sql, Object... args) {
        try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fila(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el cliente", e);
        }
    }

    private static ClienteFila fila(ResultSet rs) throws SQLException {
        return new ClienteFila(rs.getObject("id", UUID.class), rs.getString("nombre"), rs.getString("tipo_identificacion"),
                rs.getString("identificacion"), rs.getString("telefono"), rs.getString("email"));
    }

    private static DireccionFila filaDireccion(ResultSet rs) throws SQLException {
        return new DireccionFila(rs.getObject("id", UUID.class), rs.getObject("cliente_id", UUID.class),
                rs.getString("etiqueta"), rs.getString("direccion"), rs.getString("referencia"));
    }
}
