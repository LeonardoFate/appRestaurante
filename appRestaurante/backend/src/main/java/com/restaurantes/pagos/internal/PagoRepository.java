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

    /** Un pago con su estado (para reembolsar). */
    record PagoFila(UUID id, UUID pedidoId, MetodoPago metodo, BigDecimal monto, String estado) {
    }

    record ReembolsoGuardado(UUID id, UUID pagoId, BigDecimal monto) {
    }

    @Inject
    TenantTransaction tx;

    /** Serializa operaciones concurrentes sobre el mismo pago (idempotencia). */
    void bloquear(UUID pagoId) {
        bloquearClave("pago:" + pagoId);
    }

    /** Serializa operaciones concurrentes sobre el mismo reembolso (idempotencia). */
    void bloquearReembolso(UUID reembolsoId) {
        bloquearClave("reembolso:" + reembolsoId);
    }

    private void bloquearClave(String clave) {
        try (PreparedStatement ps = tx.connection().prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            ps.setString(1, clave);
            ps.execute();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo bloquear la operacion", e);
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

    /** @param bloquear si es true, bloquea la fila del pago hasta el fin de la transaccion */
    Optional<PagoFila> pago(UUID pagoId, boolean bloquear) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select id, pedido_id, metodo, monto, estado from pagos where id = ?" + (bloquear ? " for update" : ""))) {
            ps.setObject(1, pagoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PagoFila(rs.getObject("id", UUID.class), rs.getObject("pedido_id", UUID.class),
                        MetodoPago.desde(rs.getString("metodo")), rs.getBigDecimal("monto"), rs.getString("estado")));
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el pago", e);
        }
    }

    /**
     * Cobrado NETO de un pedido: lo cobrado y vigente menos lo ya reembolsado. Un pago reembolsado parcialmente solo cuenta
     * por la parte que sigue cobrada.
     */
    BigDecimal sumaCobrada(UUID pedidoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                select coalesce(sum(p.monto - coalesce((select sum(r.monto) from reembolsos r
                                                         where r.pago_id = p.id and r.estado in ('pendiente', 'completado')), 0)), 0)
                  from pagos p
                 where p.pedido_id = ? and p.estado in ('pendiente', 'autorizado', 'capturado', 'reembolsado_parcial')
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

    /** Lo ya reembolsado de un pago. */
    BigDecimal reembolsado(UUID pagoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select coalesce(sum(monto), 0) from reembolsos where pago_id = ? and estado in ('pendiente', 'completado')")) {
            ps.setObject(1, pagoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo sumar los reembolsos", e);
        }
    }

    /** El pedido tiene (o tuvo) algun reembolso vigente. */
    boolean hayReembolsos(UUID pedidoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                select 1 from reembolsos r join pagos p on p.id = r.pago_id and p.tenant_id = r.tenant_id
                 where p.pedido_id = ? and r.estado in ('pendiente', 'completado') limit 1
                """)) {
            ps.setObject(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo consultar los reembolsos del pedido", e);
        }
    }

    Optional<ReembolsoGuardado> reembolso(UUID reembolsoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("select id, pago_id, monto from reembolsos where id = ?")) {
            ps.setObject(1, reembolsoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new ReembolsoGuardado(rs.getObject("id", UUID.class), rs.getObject("pago_id", UUID.class),
                                rs.getBigDecimal("monto")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el reembolso", e);
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

    /** Registra un reembolso ya entregado (efectivo, o reverso manual de un datafono o transferencia). */
    void insertarReembolso(UUID id, UUID pagoId, BigDecimal monto, String motivo, UUID autorizadoPor) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into reembolsos(id, tenant_id, pago_id, monto, motivo, autorizado_por, estado)
                values (?, ?, ?, ?, ?, ?, 'completado')
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, tx.tenantActual());
            ps.setObject(3, pagoId);
            ps.setBigDecimal(4, monto);
            ps.setString(5, motivo);
            ps.setObject(6, autorizadoPor);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo guardar el reembolso", e);
        }
    }

    void actualizarEstadoDelPago(UUID pagoId, String estado) {
        try (PreparedStatement ps = tx.connection().prepareStatement("update pagos set estado = ? where id = ?")) {
            ps.setString(1, estado);
            ps.setObject(2, pagoId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo actualizar el estado del pago", e);
        }
    }
}
