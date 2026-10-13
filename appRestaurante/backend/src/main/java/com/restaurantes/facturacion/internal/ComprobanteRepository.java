package com.restaurantes.facturacion.internal;

import com.restaurantes.facturacion.domain.Receptor;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.shared.tenancy.TenantTransaction;
import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL explicito del modulo facturacion. Usa la conexion de la unidad de trabajo; no abre transacciones. */
@ApplicationScoped
class ComprobanteRepository {

    /** Factura vigente de un pedido, con lo necesario para acreditarla. */
    record FacturaVigente(UUID id, UUID sucursalId, String ambiente, String secuencial, LocalDate fechaEmision,
                          Receptor receptor, BigDecimal subtotalSinImpuestos, BigDecimal totalIva, BigDecimal propina,
                          BigDecimal importeTotal) {
    }

    @Inject
    TenantTransaction tx;

    boolean existeFacturaVigente(UUID pedidoId) {
        return facturaVigente(pedidoId).isPresent();
    }

    Optional<FacturaVigente> facturaVigente(UUID pedidoId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                select id, sucursal_id, ambiente, secuencial, fecha_emision, receptor_tipo_identificacion,
                       receptor_identificacion, receptor_razon_social, receptor_email,
                       subtotal_sin_impuestos, total_iva, propina, importe_total
                  from comprobantes where pedido_id = ? and tipo = '01' and estado_sri <> 'anulado'
                """)) {
            ps.setObject(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new FacturaVigente(rs.getObject("id", UUID.class), rs.getObject("sucursal_id", UUID.class),
                        rs.getString("ambiente"), rs.getString("secuencial"), rs.getObject("fecha_emision", LocalDate.class),
                        new Receptor(rs.getString("receptor_tipo_identificacion"), rs.getString("receptor_identificacion"),
                                rs.getString("receptor_razon_social"), rs.getString("receptor_email")),
                        rs.getBigDecimal("subtotal_sin_impuestos"), rs.getBigDecimal("total_iva"), rs.getBigDecimal("propina"),
                        rs.getBigDecimal("importe_total")));
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer la factura del pedido", e);
        }
    }

    boolean existeNotaDeCredito(UUID facturaId) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select 1 from comprobantes where comprobante_modificado_id = ? and tipo = '04' and estado_sri <> 'anulado'")) {
            ps.setObject(1, facturaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo comprobar la nota de credito", e);
        }
    }

    /** Numero siguiente del contador transaccional: si la transaccion se revierte, el numero no se consume. */
    String siguienteSecuencial(UUID sucursalId, String tipoComprobante, String ambiente) {
        return funcion("select app.siguiente_secuencial(?, ?, ?)", sucursalId, tipoComprobante, ambiente);
    }

    String generarClaveAcceso(LocalDate fecha, String tipoComprobante, String ruc, String ambiente, String estab, String punto,
                              String secuencial) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select app.generar_clave_acceso(?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, fecha);
            ps.setString(2, tipoComprobante);
            ps.setString(3, ruc);
            ps.setString(4, ambiente);
            ps.setString(5, estab);
            ps.setString(6, punto);
            ps.setString(7, secuencial);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo generar la clave de acceso", e);
        }
    }

    void insertarFactura(UUID id, PedidoVista pedido, String ambiente, String secuencial, String claveAcceso,
                         LocalDate fechaEmision, Receptor receptor) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into comprobantes(id, tenant_id, sucursal_id, pedido_id, tipo, ambiente, secuencial, clave_acceso,
                                         fecha_emision, receptor_tipo_identificacion, receptor_identificacion,
                                         receptor_razon_social, receptor_email,
                                         subtotal_sin_impuestos, total_iva, propina, importe_total)
                values (?, ?, ?, ?, '01', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, tx.tenantActual());
            ps.setObject(3, pedido.sucursalId());
            ps.setObject(4, pedido.id());
            ps.setString(5, ambiente);
            ps.setString(6, secuencial);
            ps.setString(7, claveAcceso);
            ps.setObject(8, fechaEmision);
            ps.setString(9, receptor.tipoIdentificacion());
            ps.setString(10, receptor.identificacion());
            ps.setString(11, receptor.razonSocial());
            ps.setString(12, receptor.email());
            // Base = items + envio (el envio se factura como una linea sin IVA); la propina va aparte.
            ps.setBigDecimal(13, pedido.totales().subtotal().subtract(pedido.totales().descuento()).add(pedido.totales().costoEnvio()));
            ps.setBigDecimal(14, pedido.totales().iva());
            ps.setBigDecimal(15, pedido.totales().propina());
            ps.setBigDecimal(16, pedido.totales().total());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo guardar el comprobante", e);
        }
    }

    /** Nota de credito que acredita TODA la factura (devolucion total): mismo receptor y mismos totales. */
    void insertarNotaDeCredito(UUID id, UUID pedidoId, FacturaVigente factura, String secuencial, String claveAcceso,
                               LocalDate fechaEmision, String motivo) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into comprobantes(id, tenant_id, sucursal_id, pedido_id, tipo, comprobante_modificado_id,
                                         motivo_modificacion, ambiente, secuencial, clave_acceso, fecha_emision,
                                         receptor_tipo_identificacion, receptor_identificacion, receptor_razon_social,
                                         receptor_email, subtotal_sin_impuestos, total_iva, propina, importe_total)
                values (?, ?, ?, ?, '04', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, tx.tenantActual());
            ps.setObject(3, factura.sucursalId());
            ps.setObject(4, pedidoId);
            ps.setObject(5, factura.id());
            ps.setString(6, motivo);
            ps.setString(7, factura.ambiente());
            ps.setString(8, secuencial);
            ps.setString(9, claveAcceso);
            ps.setObject(10, fechaEmision);
            ps.setString(11, factura.receptor().tipoIdentificacion());
            ps.setString(12, factura.receptor().identificacion());
            ps.setString(13, factura.receptor().razonSocial());
            ps.setString(14, factura.receptor().email());
            ps.setBigDecimal(15, factura.subtotalSinImpuestos());
            ps.setBigDecimal(16, factura.totalIva());
            ps.setBigDecimal(17, factura.propina());
            ps.setBigDecimal(18, factura.importeTotal());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo guardar la nota de credito", e);
        }
    }

    /** Copia los detalles de la factura a la nota de credito (la acredita completa). */
    void copiarDetalles(UUID desdeComprobanteId, UUID haciaComprobanteId) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into comprobante_detalles(tenant_id, comprobante_id, linea, codigo, descripcion, cantidad, precio_unitario,
                                                 descuento, base_imponible, codigo_iva_sri, tarifa_iva, valor_iva)
                select tenant_id, ?, linea, codigo, descripcion, cantidad, precio_unitario,
                       descuento, base_imponible, codigo_iva_sri, tarifa_iva, valor_iva
                  from comprobante_detalles where comprobante_id = ?
                """)) {
            ps.setObject(1, haciaComprobanteId);
            ps.setObject(2, desdeComprobanteId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron copiar los detalles a la nota de credito", e);
        }
    }

    void insertarDetalles(UUID comprobanteId, List<PedidoVista.Linea> lineas, BigDecimal costoEnvio) {
        try (PreparedStatement ps = tx.connection().prepareStatement("""
                insert into comprobante_detalles(tenant_id, comprobante_id, linea, codigo, descripcion, cantidad,
                                                 precio_unitario, descuento, base_imponible, codigo_iva_sri, tarifa_iva, valor_iva)
                values (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                """)) {
            int numero = 1;
            for (PedidoVista.Linea l : lineas) {
                ps.setObject(1, tx.tenantActual());
                ps.setObject(2, comprobanteId);
                ps.setInt(3, numero++);
                ps.setString(4, l.productoId() != null ? l.productoId().toString() : l.id().toString());
                ps.setString(5, l.nombre());
                ps.setBigDecimal(6, l.cantidad());
                ps.setBigDecimal(7, l.precioUnitario());
                ps.setBigDecimal(8, l.subtotal());
                ps.setString(9, l.codigoIvaSri());
                ps.setBigDecimal(10, l.porcentajeIva());
                ps.setBigDecimal(11, l.iva());
                ps.addBatch();
            }
            if (costoEnvio.signum() > 0) {
                // SUPUESTO no verificado con el SRI: el envio es una linea propia sin IVA (codigo 0).
                ps.setObject(1, tx.tenantActual());
                ps.setObject(2, comprobanteId);
                ps.setInt(3, numero);
                ps.setString(4, "ENVIO");
                ps.setString(5, "Servicio de entrega");
                ps.setBigDecimal(6, BigDecimal.ONE);
                ps.setBigDecimal(7, costoEnvio);
                ps.setBigDecimal(8, costoEnvio);
                ps.setString(9, "0");
                ps.setBigDecimal(10, BigDecimal.ZERO);
                ps.setBigDecimal(11, BigDecimal.ZERO);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron guardar los detalles del comprobante", e);
        }
    }

    void registrarEvento(UUID comprobanteId, String estado, String respuesta) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "insert into comprobante_eventos(tenant_id, comprobante_id, estado, respuesta) values (?, ?, ?, ?)")) {
            ps.setObject(1, tx.tenantActual());
            ps.setObject(2, comprobanteId);
            ps.setString(3, estado);
            ps.setString(4, respuesta);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo registrar el evento del comprobante", e);
        }
    }

    private String funcion(String sql, Object... parametros) {
        Connection c = tx.connection();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setObject(i + 1, parametros[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo ejecutar " + sql, e);
        }
    }
}
