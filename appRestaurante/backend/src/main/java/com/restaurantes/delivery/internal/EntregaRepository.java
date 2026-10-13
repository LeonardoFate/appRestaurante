package com.restaurantes.delivery.internal;

import com.restaurantes.delivery.domain.EstadoEntrega;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL explicito de las entregas. Los listados LEEN (sin escribir) algunas columnas de {@code pedidos} y del cliente para
 * mostrar el reparto; el estado del pedido lo cambia siempre el modulo pedidos.
 */
@ApplicationScoped
class EntregaRepository {

    record EntregaFila(UUID id, UUID pedidoId, UUID repartidorId, EstadoEntrega estado, BigDecimal efectivoPorCobrar,
                       boolean liquidada) {
    }

    /** Fila de listado con lo que necesita el despacho o el repartidor. */
    record EntregaDetalle(UUID pedidoId, Integer numeroPedido, String estado, UUID repartidorId, String estadoPedido,
                          String estadoPago, BigDecimal total, String cliente, String telefono, String direccion,
                          String referencia, BigDecimal efectivoPorCobrar, boolean liquidada) {
    }

    record PendienteDeLiquidar(UUID repartidorId, int entregas, BigDecimal efectivo) {
    }

    @Inject
    TenantTransaction tx;

    /** Crea la entrega pendiente si no existe (la reentrega del evento no la duplica). */
    void crearSiNoExiste(UUID pedidoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "insert into entregas(id, tenant_id, pedido_id) values (?, ?, ?) on conflict (tenant_id, pedido_id) do nothing")) {
            ps.setObject(1, UuidV7.next());
            ps.setObject(2, tx.tenantActual());
            ps.setObject(3, pedidoId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo crear la entrega", e);
        }
    }

    Optional<EntregaFila> porPedido(UUID pedidoId, boolean bloquear) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select id, pedido_id, repartidor_id, estado, efectivo_por_cobrar, efectivo_liquidado_en is not null as liquidada "
                        + "from entregas where pedido_id = ?" + (bloquear ? " for update" : ""))) {
            ps.setObject(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new EntregaFila(rs.getObject("id", UUID.class), rs.getObject("pedido_id", UUID.class),
                        rs.getObject("repartidor_id", UUID.class), EstadoEntrega.desde(rs.getString("estado")),
                        rs.getBigDecimal("efectivo_por_cobrar"), rs.getBoolean("liquidada")));
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer la entrega", e);
        }
    }

    void asignar(UUID pedidoId, UUID repartidorId) {
        actualizar("update entregas set repartidor_id = ?, estado = 'asignado', asignado_en = now(), actualizado_en = now() "
                + "where pedido_id = ?", repartidorId, pedidoId);
    }

    void recoger(UUID pedidoId) {
        actualizar("update entregas set estado = 'recogido', recogido_en = now(), actualizado_en = now() where pedido_id = ?", pedidoId);
    }

    void entregar(UUID pedidoId, BigDecimal efectivoPorCobrar) {
        actualizar("update entregas set estado = 'entregado', entregado_en = now(), efectivo_por_cobrar = ?, actualizado_en = now() "
                + "where pedido_id = ?", efectivoPorCobrar, pedidoId);
    }

    void fallar(UUID pedidoId, String motivo) {
        actualizar("update entregas set estado = 'fallido', motivo_fallo = ?, actualizado_en = now() where pedido_id = ?", motivo, pedidoId);
    }

    /** Marca como fallida una entrega que sigue en curso (no toca las ya entregadas ni las finales). */
    void fallarSiEnCurso(UUID pedidoId, String motivo) {
        actualizar("update entregas set estado = 'fallido', motivo_fallo = ?, actualizado_en = now() "
                + "where pedido_id = ? and estado in ('pendiente', 'asignado', 'recogido')", motivo, pedidoId);
    }

    List<EntregaDetalle> listar(UUID sucursalId, List<String> estados, UUID repartidorId) {
        String sql = """
                select e.pedido_id, p.numero, e.estado, e.repartidor_id, p.estado as estado_pedido, p.estado_pago, p.total,
                       c.nombre, c.telefono, d.direccion, d.referencia, e.efectivo_por_cobrar,
                       e.efectivo_liquidado_en is not null as liquidada
                  from entregas e
                  join pedidos p on p.tenant_id = e.tenant_id and p.id = e.pedido_id
                  left join clientes c on c.tenant_id = p.tenant_id and c.id = p.cliente_id
                  left join direcciones_cliente d on d.tenant_id = p.tenant_id and d.id = p.direccion_id
                 where p.sucursal_id = ? and e.estado = any (?)
                """ + (repartidorId != null ? " and e.repartidor_id = ?" : "") + " order by p.recibido_en, e.id";
        List<EntregaDetalle> filas = new ArrayList<>();
        try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
            ps.setObject(1, sucursalId);
            ps.setArray(2, tx.connection().createArrayOf("text", estados.toArray()));
            if (repartidorId != null) {
                ps.setObject(3, repartidorId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int numero = rs.getInt("numero");
                    Integer numeroPedido = rs.wasNull() ? null : numero;
                    filas.add(new EntregaDetalle(rs.getObject("pedido_id", UUID.class), numeroPedido, rs.getString("estado"),
                            rs.getObject("repartidor_id", UUID.class), rs.getString("estado_pedido"), rs.getString("estado_pago"),
                            rs.getBigDecimal("total"), rs.getString("nombre"), rs.getString("telefono"), rs.getString("direccion"),
                            rs.getString("referencia"), rs.getBigDecimal("efectivo_por_cobrar"), rs.getBoolean("liquidada")));
                }
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron listar las entregas", e);
        }
        return filas;
    }

    /** Efectivo cobrado por cada repartidor y aun no entregado a caja, de la sucursal. */
    List<PendienteDeLiquidar> pendientesDeLiquidar(UUID sucursalId) {
        List<PendienteDeLiquidar> filas = new ArrayList<>();
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                select e.repartidor_id, count(*) as entregas, sum(e.efectivo_por_cobrar) as efectivo
                  from entregas e join pedidos p on p.tenant_id = e.tenant_id and p.id = e.pedido_id
                 where p.sucursal_id = ? and e.estado = 'entregado' and e.efectivo_por_cobrar > 0
                   and e.efectivo_liquidado_en is null
                 group by e.repartidor_id order by e.repartidor_id
                """)) {
            ps.setObject(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    filas.add(new PendienteDeLiquidar(rs.getObject("repartidor_id", UUID.class), rs.getInt("entregas"),
                            rs.getBigDecimal("efectivo")));
                }
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el efectivo pendiente", e);
        }
        return filas;
    }

    /**
     * Liquida de una vez todo el efectivo pendiente del repartidor en la sucursal. Una sola sentencia: dos liquidaciones
     * simultaneas no cuentan la misma entrega dos veces (la segunda ya no encuentra filas pendientes).
     */
    PendienteDeLiquidar liquidar(UUID repartidorId, UUID sucursalId, UUID usuarioId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                update entregas e set efectivo_liquidado_en = now(), liquidado_por = ?, actualizado_en = now()
                  from pedidos p
                 where p.tenant_id = e.tenant_id and p.id = e.pedido_id and p.sucursal_id = ?
                   and e.repartidor_id = ? and e.estado = 'entregado' and e.efectivo_por_cobrar > 0
                   and e.efectivo_liquidado_en is null
                returning e.efectivo_por_cobrar
                """)) {
            ps.setObject(1, usuarioId);
            ps.setObject(2, sucursalId);
            ps.setObject(3, repartidorId);
            int entregas = 0;
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entregas++;
                    total = total.add(rs.getBigDecimal(1));
                }
            }
            return new PendienteDeLiquidar(repartidorId, entregas, total);
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo liquidar el efectivo", e);
        }
    }

    private void actualizar(String sql, Object... args) {
        try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo actualizar la entrega", e);
        }
    }
}
