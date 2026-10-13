package com.restaurantes.cocina.internal;

import com.restaurantes.pedidos.domain.CanalPedido;
import com.restaurantes.pedidos.domain.EstadoCocina;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL explicito del modulo cocina. Usa la conexion de la unidad de trabajo; no abre transacciones.
 *
 * <p>Propiedad de los datos: {@code estaciones_cocina} es de cocina. En {@code pedido_items} la fila (y el snapshot de
 * precio e IVA) es de {@code pedidos}, pero las COLUMNAS de cocina ({@code estado_cocina}, {@code estacion_id},
 * {@code enviado_en}, {@code preparando_en}, {@code listo_en}, {@code servido_en}, {@code motivo_anulacion},
 * {@code anulado_por}) las gestiona solo este modulo.
 */
@ApplicationScoped
class CocinaRepository {

    record Estacion(UUID id, String nombre) {
    }

    /** Item tal como lo ve la pantalla de cocina. */
    record ItemKds(UUID itemId, UUID pedidoId, Integer numeroPedido, CanalPedido canal, UUID mesaId, String nombre,
                   BigDecimal cantidad, String nota, EstadoCocina estado, UUID estacionId, String tiempoServicio,
                   OffsetDateTime enviadoEn) {
    }

    /** Lo minimo de un item para decidir si se puede cambiar su estado. */
    record ItemBase(UUID itemId, UUID pedidoId, UUID sucursalId, EstadoCocina estado) {
    }

    private static final String SELECT_ITEM = """
            select i.id as item_id, i.pedido_id, p.numero, p.canal, p.mesa_id, i.nombre, i.cantidad, i.nota,
                   i.estado_cocina, i.estacion_id, i.tiempo_servicio, i.enviado_en
              from pedido_items i
              join pedidos p on p.id = i.pedido_id and p.tenant_id = i.tenant_id
            """;

    @Inject
    TenantTransaction tx;

    List<Estacion> estaciones(UUID sucursalId) {
        List<Estacion> estaciones = new ArrayList<>();
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select id, nombre from estaciones_cocina where sucursal_id = ? and activa order by nombre, id")) {
            ps.setObject(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    estaciones.add(new Estacion(rs.getObject("id", UUID.class), rs.getString("nombre")));
                }
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron leer las estaciones", e);
        }
        return estaciones;
    }

    /** Estacion a la que van los items cuyo producto no tiene una asignada: la primera activa por nombre. */
    Optional<UUID> estacionPorDefecto(UUID sucursalId) {
        return estaciones(sucursalId).stream().findFirst().map(Estacion::id);
    }

    /**
     * Items en curso (enviados, preparando o listos) de una sucursal, del mas antiguo al mas reciente. Excluye los
     * pedidos cancelados o cerrados. Si se indica estacion, solo los de esa estacion.
     */
    List<ItemKds> itemsActivos(UUID sucursalId, UUID estacionId) {
        List<ItemKds> items = new ArrayList<>();
        try (PreparedStatement ps = tx.connection().prepareStatement(SELECT_ITEM + """
                 where p.sucursal_id = ?
                   and i.estado_cocina in ('enviado', 'preparando', 'listo')
                   and p.estado not in ('cancelado', 'cerrado', 'devuelto_parcial', 'devuelto_total')
                   and (?::uuid is null or i.estacion_id = ?::uuid)
                 order by i.enviado_en, i.id
                """)) {
            ps.setObject(1, sucursalId);
            ps.setObject(2, estacionId);
            ps.setObject(3, estacionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapear(rs));
                }
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron leer los items de cocina", e);
        }
        return items;
    }

    /** Un item en cualquier estado (para responder al cambiar su estado). */
    Optional<ItemKds> vista(UUID itemId) {
        try (PreparedStatement ps = tx.connection().prepareStatement(SELECT_ITEM + " where i.id = ?")) {
            ps.setObject(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el item", e);
        }
    }

    /** @param bloquear si es true, bloquea la fila del item ({@code FOR UPDATE OF i}) hasta el fin de la transaccion */
    Optional<ItemBase> base(UUID itemId, boolean bloquear) {
        String sql = """
                select i.id, i.pedido_id, p.sucursal_id, i.estado_cocina
                  from pedido_items i
                  join pedidos p on p.id = i.pedido_id and p.tenant_id = i.tenant_id
                 where i.id = ?
                """ + (bloquear ? " for update of i" : "");
        try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
            ps.setObject(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ItemBase(rs.getObject("id", UUID.class), rs.getObject("pedido_id", UUID.class),
                        rs.getObject("sucursal_id", UUID.class), EstadoCocina.desde(rs.getString("estado_cocina"))));
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el item", e);
        }
    }

    /** Cambia el estado de un item y su marca de tiempo. La validez de la transicion la decide el servicio. */
    void cambiarEstado(UUID itemId, EstadoCocina nuevo, String motivo, UUID usuarioId) {
        String sql = switch (nuevo) {
            case ENVIADO -> "update pedido_items set estado_cocina = 'enviado', enviado_en = now() where id = ?";
            case PREPARANDO -> "update pedido_items set estado_cocina = 'preparando', preparando_en = now() where id = ?";
            case LISTO -> "update pedido_items set estado_cocina = 'listo', listo_en = now(), "
                    + "preparando_en = coalesce(preparando_en, now()) where id = ?";
            case SERVIDO -> "update pedido_items set estado_cocina = 'servido', servido_en = now() where id = ?";
            case ANULADO -> "update pedido_items set estado_cocina = 'anulado', motivo_anulacion = ?, anulado_por = ? where id = ?";
            case PENDIENTE -> throw new IllegalArgumentException("Un item no vuelve a 'pendiente'");
        };
        try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
            if (nuevo == EstadoCocina.ANULADO) {
                ps.setString(1, motivo);
                ps.setObject(2, usuarioId);
                ps.setObject(3, itemId);
            } else {
                ps.setObject(1, itemId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo cambiar el estado del item", e);
        }
    }

    /** Pasa a "enviado" los items aun pendientes del pedido, asignando la estacion por defecto a los que no tienen. */
    int enviarItems(UUID pedidoId, UUID estacionPorDefecto) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                update pedido_items
                   set estado_cocina = 'enviado', enviado_en = now(), estacion_id = coalesce(estacion_id, ?)
                 where pedido_id = ? and estado_cocina = 'pendiente'
                """)) {
            ps.setObject(1, estacionPorDefecto);
            ps.setObject(2, pedidoId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron enviar los items a cocina", e);
        }
    }

    /** Anula los items de un pedido cancelado que aun no se sirvieron. */
    int anularItemsDeCancelado(UUID pedidoId, String motivo, UUID usuarioId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                update pedido_items set estado_cocina = 'anulado', motivo_anulacion = ?, anulado_por = ?
                 where pedido_id = ? and estado_cocina not in ('servido', 'anulado')
                """)) {
            ps.setString(1, motivo);
            ps.setObject(2, usuarioId);
            ps.setObject(3, pedidoId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron anular los items del pedido cancelado", e);
        }
    }

    private static ItemKds mapear(ResultSet rs) throws SQLException {
        int numero = rs.getInt("numero");
        boolean sinNumero = rs.wasNull();   // debe leerse justo despues de la columna
        return new ItemKds(rs.getObject("item_id", UUID.class), rs.getObject("pedido_id", UUID.class),
                sinNumero ? null : numero, CanalPedido.desde(rs.getString("canal")), rs.getObject("mesa_id", UUID.class),
                rs.getString("nombre"), rs.getBigDecimal("cantidad"), rs.getString("nota"),
                EstadoCocina.desde(rs.getString("estado_cocina")), rs.getObject("estacion_id", UUID.class),
                rs.getString("tiempo_servicio"), rs.getObject("enviado_en", OffsetDateTime.class));
    }
}
