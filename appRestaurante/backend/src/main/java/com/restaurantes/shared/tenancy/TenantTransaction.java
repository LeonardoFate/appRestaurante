package com.restaurantes.shared.tenancy;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Unica via de acceso a la base de datos del negocio (ADR-002).
 *
 * <p><b>Unidad de trabajo.</b> {@link #withTenant} abre una transaccion, fija
 * {@code app.tenant_id} con alcance de transaccion ({@code set_config(..., true)}, equivalente
 * a {@code SET LOCAL}) y ejecuta el trabajo. Al terminar (commit o rollback) PostgreSQL descarta
 * el valor, de modo que la conexion puede reutilizarse con PgBouncer en modo {@code transaction}
 * sin filtrar datos. Nunca se debe usar {@code SET} de sesion (F-001).
 *
 * <p><b>Los repositorios no abren transacciones</b>: usan {@link #connection()}, la conexion de la
 * unidad de trabajo que abrio el caso de uso (el endpoint REST o el job). Asi un caso de uso que
 * cruza modulos (pedidos, pagos, outbox) es una sola transaccion y no hay forma de ejecutar SQL
 * fuera del contexto de un tenant.
 *
 * <p>Una llamada anidada con el mismo tenant se une a la unidad existente; con otro tenant falla.
 */
@ApplicationScoped
public class TenantTransaction {

    private record Unidad(UUID tenantId, Connection connection) {
    }

    private static final ThreadLocal<Unidad> ACTUAL = new ThreadLocal<>();

    @Inject
    AgroalDataSource dataSource;

    /** Trabajo SQL que se ejecuta dentro de la transaccion del tenant. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Variante sin resultado. */
    @FunctionalInterface
    public interface SqlAction {
        void run(Connection connection) throws SQLException;
    }

    public <T> T withTenant(UUID tenantId, SqlWork<T> work) {
        Objects.requireNonNull(tenantId, "tenantId es obligatorio");
        Objects.requireNonNull(work, "work es obligatorio");

        Unidad existente = ACTUAL.get();
        if (existente != null) {
            if (!existente.tenantId().equals(tenantId)) {
                throw new IllegalStateException("Ya hay una transaccion abierta para otro tenant");
            }
            return ejecutarUnido(existente, tenantId, work);
        }

        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommitPrevio = connection.getAutoCommit();
            connection.setAutoCommit(false);
            ACTUAL.set(new Unidad(tenantId, connection));
            try {
                fijarTenant(connection, tenantId);
                T resultado = work.apply(connection);
                connection.commit();
                return resultado;
            } catch (SQLException error) {
                rollbackSilencioso(connection, error);
                throw new TenantTransactionException("Fallo SQL en la transaccion del tenant " + tenantId, error);
            } catch (RuntimeException | Error error) {
                rollbackSilencioso(connection, error);
                throw error;
            } finally {
                ACTUAL.remove();
                connection.setAutoCommit(autoCommitPrevio);
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo abrir la transaccion del tenant " + tenantId, e);
        }
    }

    /** Igual que {@link #withTenant} para trabajo sin resultado (nombre distinto para evitar sobrecarga ambigua con lambdas). */
    public void runInTenant(UUID tenantId, SqlAction action) {
        withTenant(tenantId, connection -> {
            action.run(connection);
            return null;
        });
    }

    /** Conexion de la unidad de trabajo actual. Falla si no hay una (el SQL fuera de un tenant esta prohibido). */
    public Connection connection() {
        return unidadActual().connection();
    }

    /** Tenant de la unidad de trabajo actual. */
    public UUID tenantActual() {
        return unidadActual().tenantId();
    }

    private static Unidad unidadActual() {
        Unidad unidad = ACTUAL.get();
        if (unidad == null) {
            throw new IllegalStateException("No hay una transaccion de tenant abierta: use TenantTransaction.withTenant");
        }
        return unidad;
    }

    private static <T> T ejecutarUnido(Unidad unidad, UUID tenantId, SqlWork<T> work) {
        try {
            return work.apply(unidad.connection());
        } catch (SQLException e) {
            throw new TenantTransactionException("Fallo SQL en la transaccion del tenant " + tenantId, e);
        }
    }

    private static void fijarTenant(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenantId.toString());
            ps.execute();
        }
    }

    private static void rollbackSilencioso(Connection connection, Throwable causa) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            causa.addSuppressed(e);
        }
    }
}
