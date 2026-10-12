package com.restaurantes.pagos.internal;

import com.restaurantes.pagos.domain.MetodoPago;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** SQL explicito del modulo pagos. Usa la conexion de la unidad de trabajo; no abre transacciones. */
@ApplicationScoped
class PagoRepository {

    record PagoGuardado(UUID id, UUID pedidoId, MetodoPago metodo, BigDecimal monto) {
    }

    @Inject
    TenantTransaction tx;

    /** Serializa operaciones concurrentes sobre el mismo pago (idempotencia). */
    void bloquear(UUID pagoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            ps.setString(1, "pago:" + pagoId);
            ps.execute();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo bloquear el pago", e);
        }
    }

    Optional<PagoGuardado> buscar(UUID pagoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select id, pedido_id, metodo, monto from pagos where id = ?")) {
            ps.setObject(1, pagoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PagoGuardado(rs.getObject("id", UUID.class), rs.getObject("pedido_id", UUID.class),
                        MetodoPago.desde(rs.getString("metodo")), rs.getBigDecimal("monto")));
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el pago", e);
        }
    }

    /** Suma de lo cobrado y vigente de un pedido. */
    BigDecimal sumaCobrada(UUID pedidoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                select coalesce(sum(monto), 0) from pagos
                 where pedido_id = ? and estado in ('pendiente', 'autorizado', 'capturado', 'reembolsado_parcial')
                """)) {
            ps.setObject(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo sumar los pagos", e);
        }
    }

    void insertar(UUID id, UUID pedidoId, MetodoPago metodo, BigDecimal monto) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into pagos(id, tenant_id, pedido_id, metodo, monto, estado)
                values (?, ?, ?, ?, ?, 'capturado')
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, tx.tenantActual());
            ps.setObject(3, pedidoId);
            ps.setString(4, metodo.valor());
            ps.setBigDecimal(5, monto);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo guardar el pago", e);
        }
    }
}
