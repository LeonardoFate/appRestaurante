package com.restaurantes.pedidos.internal;

import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.domain.CanalPedido;
import com.restaurantes.pedidos.domain.EstadoCocina;
import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.pedidos.domain.EstadoPedido;
import com.restaurantes.pedidos.domain.LineaPedido;
import com.restaurantes.pedidos.domain.Totales;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL explicito del modulo pedidos. Usa la conexion de la unidad de trabajo; no abre transacciones. */
@ApplicationScoped
class PedidoRepository {

    @Inject
    TenantTransaction tx;

    /**
     * Serializa operaciones concurrentes sobre el mismo id (idempotencia sin huecos de numeracion): quien llega
     * segundo espera al primero y despues ve el pedido ya creado.
     */
    void bloquearCreacion(UUID pedidoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            ps.setString(1, "pedido:" + pedidoId);
            ps.execute();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo bloquear la creacion del pedido", e);
        }
    }

    int siguienteNumero(UUID sucursalId, LocalDate fechaOperativa) {
        try (PreparedStatement ps = tx.connection().prepareStatement("select app.siguiente_numero_pedido(?, ?)")) {
            ps.setObject(1, sucursalId);
            ps.setObject(2, fechaOperativa);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo asignar el numero de pedido", e);
        }
    }

    void insertar(UUID id, UUID sucursalId, int numero, CanalPedido canal, UUID mesaId, UUID usuarioId,
                  LocalDate fechaOperativa, Totales t, List<LineaPedido> lineas) {
        Connection c = tx.connection();
        UUID tenant = tx.tenantActual();
        try {
            try (PreparedStatement ps = c.prepareStatement("""
                    insert into pedidos(id, tenant_id, sucursal_id, numero, canal, estado, estado_pago, momento_pago,
                                        mesa_id, usuario_id, fecha_operativa,
                                        subtotal, descuento, iva, propina, costo_envio, total)
                    values (?, ?, ?, ?, ?, 'creado', 'sin_pago', 'al_final', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setObject(1, id);
                ps.setObject(2, tenant);
                ps.setObject(3, sucursalId);
                ps.setInt(4, numero);
                ps.setString(5, canal.valor());
                ps.setObject(6, mesaId);
                ps.setObject(7, usuarioId);
                ps.setObject(8, fechaOperativa);
                ps.setBigDecimal(9, t.subtotal());
                ps.setBigDecimal(10, t.descuento());
                ps.setBigDecimal(11, t.iva());
                ps.setBigDecimal(12, t.propina());
                ps.setBigDecimal(13, t.costoEnvio());
                ps.setBigDecimal(14, t.total());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    insert into pedido_items(id, tenant_id, pedido_id, producto_id, nombre, precio_unitario, tarifa_iva,
                                             codigo_iva_sri, cantidad, nota, estacion_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (LineaPedido l : lineas) {
                    ps.setObject(1, l.id());
                    ps.setObject(2, tenant);
                    ps.setObject(3, id);
                    ps.setObject(4, l.productoId());
                    ps.setString(5, l.nombre());
                    ps.setBigDecimal(6, l.precioUnitario());
                    ps.setBigDecimal(7, l.porcentajeIva());
                    ps.setString(8, l.codigoIvaSri());
                    ps.setBigDecimal(9, l.cantidad());
                    ps.setString(10, l.nota());
                    ps.setObject(11, l.estacionId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            registrarHistorial(id, null, EstadoPedido.CREADO, usuarioId, null, "api");
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo guardar el pedido", e);
        }
    }

    /** @param bloquear si es true, toma el bloqueo de fila ({@code FOR UPDATE}) hasta el fin de la transaccion */
    Optional<PedidoVista> buscar(UUID id, boolean bloquear) {
        Connection c = tx.connection();
        try {
            PedidoVista cabecera;
            String sql = """
                    select id, sucursal_id, numero, canal, estado, estado_pago, subtotal, descuento, iva, propina,
                           costo_envio, total
                      from pedidos where id = ?
                    """ + (bloquear ? " for update" : "");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    Totales totales = new Totales(rs.getBigDecimal("subtotal"), rs.getBigDecimal("descuento"),
                            rs.getBigDecimal("iva"), rs.getBigDecimal("propina"), rs.getBigDecimal("costo_envio"),
                            rs.getBigDecimal("total"));
                    int numero = rs.getInt("numero");
                    boolean sinNumero = rs.wasNull();   // debe leerse justo despues de la columna
                    cabecera = new PedidoVista(
                            rs.getObject("id", UUID.class),
                            rs.getObject("sucursal_id", UUID.class),
                            sinNumero ? null : numero,
                            CanalPedido.desde(rs.getString("canal")),
                            rs.getString("estado"),
                            EstadoPago.desde(rs.getString("estado_pago")),
                            totales, List.of(), false);
                }
            }
            List<PedidoVista.Linea> lineas = new ArrayList<>();
            for (LineaPedido l : lineasVigentes(id)) {
                lineas.add(new PedidoVista.Linea(l.id(), l.productoId(), l.nombre(), l.precioUnitario(), l.porcentajeIva(),
                        l.codigoIvaSri(), l.cantidad(), l.nota(), l.subtotal(), l.iva()));
            }
            return Optional.of(new PedidoVista(cabecera.id(), cabecera.sucursalId(), cabecera.numero(), cabecera.canal(),
                    cabecera.estado(), cabecera.estadoPago(), cabecera.totales(), lineas, false));
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el pedido", e);
        }
    }

    /**
     * Lineas que cuentan para el dinero: las NO anuladas. Los importes de cada linea los calcula el dominio (una sola
     * fuente de verdad para el redondeo). La factura y los totales se arman solo con estas.
     */
    List<LineaPedido> lineasVigentes(UUID pedidoId) {
        List<LineaPedido> lineas = new ArrayList<>();
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                select id, producto_id, nombre, precio_unitario, tarifa_iva, codigo_iva_sri, cantidad, nota, estacion_id
                  from pedido_items where pedido_id = ? and estado_cocina <> 'anulado' order by creado_en, id
                """)) {
            ps.setObject(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lineas.add(new LineaPedido(rs.getObject("id", UUID.class), rs.getObject("producto_id", UUID.class),
                            rs.getString("nombre"), rs.getBigDecimal("precio_unitario"), rs.getBigDecimal("tarifa_iva"),
                            rs.getString("codigo_iva_sri"), rs.getBigDecimal("cantidad"), rs.getString("nota"),
                            rs.getObject("estacion_id", UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron leer las lineas del pedido", e);
        }
        return lineas;
    }

    /** Estado de cocina de TODOS los items del pedido (incluidos los anulados). */
    List<EstadoCocina> estadosDeCocina(UUID pedidoId) {
        List<EstadoCocina> estados = new ArrayList<>();
        try (PreparedStatement ps = tx.connection().prepareStatement("select estado_cocina from pedido_items where pedido_id = ?")) {
            ps.setObject(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    estados.add(EstadoCocina.desde(rs.getString(1)));
                }
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer el estado de cocina del pedido", e);
        }
        return estados;
    }

    /**
     * Cambia el estado del pedido solo si sigue en {@code desde} (nadie se adelanto) y deja constancia en el historial.
     * Debe llamarse con el pedido bloqueado.
     *
     * @param usuarioId quien lo origino; null para un proceso del sistema
     */
    void cambiarEstado(UUID id, EstadoPedido desde, EstadoPedido hasta, UUID usuarioId, String motivo, String origen) {
        try (PreparedStatement ps = tx.connection().prepareStatement("update pedidos set estado = ? where id = ? and estado = ?")) {
            ps.setString(1, hasta.valor());
            ps.setObject(2, id);
            ps.setString(3, desde.valor());
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("El pedido " + id + " ya no estaba en '" + desde.valor() + "'");
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo cambiar el estado del pedido", e);
        }
        registrarHistorial(id, desde, hasta, usuarioId, motivo, origen);
    }

    private void registrarHistorial(UUID id, EstadoPedido desde, EstadoPedido hasta, UUID usuarioId, String motivo, String origen) {
        // clock_timestamp() y no now(): varios pasos de una misma transaccion deben quedar ordenados en el historial.
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into pedido_historial(tenant_id, pedido_id, estado_anterior, estado_nuevo, usuario_id, motivo, origen,
                                             ocurrido_en)
                values (?, ?, ?, ?, ?, ?, ?, clock_timestamp())
                """)) {
            ps.setObject(1, tx.tenantActual());
            ps.setObject(2, id);
            ps.setString(3, desde == null ? null : desde.valor());
            ps.setString(4, hasta.valor());
            ps.setObject(5, usuarioId);
            ps.setString(6, motivo);
            ps.setString(7, origen);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo registrar el historial del pedido", e);
        }
    }

    void actualizarTotales(UUID id, Totales t) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                update pedidos set subtotal = ?, descuento = ?, iva = ?, propina = ?, costo_envio = ?, total = ?
                 where id = ?
                """)) {
            ps.setBigDecimal(1, t.subtotal());
            ps.setBigDecimal(2, t.descuento());
            ps.setBigDecimal(3, t.iva());
            ps.setBigDecimal(4, t.propina());
            ps.setBigDecimal(5, t.costoEnvio());
            ps.setBigDecimal(6, t.total());
            ps.setObject(7, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron actualizar los totales del pedido", e);
        }
    }

    /** @return filas afectadas (0 si el pedido no existe para este tenant) */
    int actualizarEstadoPago(UUID id, EstadoPago estadoPago) {
        try (PreparedStatement ps = tx.connection().prepareStatement("update pedidos set estado_pago = ? where id = ?")) {
            ps.setString(1, estadoPago.valor());
            ps.setObject(2, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo actualizar el estado de pago", e);
        }
    }
}
