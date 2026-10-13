package com.restaurantes.delivery.internal;

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

/** Zonas de delivery de una sucursal. Por ahora la zona la elige quien toma el pedido; el poligono no se evalua. */
@ApplicationScoped
class ZonaRepository {

    record ZonaFila(UUID id, UUID sucursalId, String nombre, BigDecimal costoEnvio, BigDecimal pedidoMinimo,
                    Integer tiempoEstimadoMin) {
    }

    private static final String COLUMNAS = "id, sucursal_id, nombre, costo_envio, pedido_minimo, tiempo_estimado_min";

    @Inject
    TenantTransaction tx;

    Optional<ZonaFila> activa(UUID zonaId, UUID sucursalId) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select " + COLUMNAS + " from zonas_delivery where id = ? and sucursal_id = ? and activa")) {
            ps.setObject(1, zonaId);
            ps.setObject(2, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fila(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudo leer la zona", e);
        }
    }

    List<ZonaFila> activasDe(UUID sucursalId) {
        List<ZonaFila> filas = new ArrayList<>();
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "select " + COLUMNAS + " from zonas_delivery where sucursal_id = ? and activa order by nombre")) {
            ps.setObject(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    filas.add(fila(rs));
                }
            }
        } catch (SQLException e) {
            throw new TenantTransactionException("No se pudieron leer las zonas", e);
        }
        return filas;
    }

    private static ZonaFila fila(ResultSet rs) throws SQLException {
        int minutos = rs.getInt("tiempo_estimado_min");
        Integer tiempo = rs.wasNull() ? null : minutos;
        return new ZonaFila(rs.getObject("id", UUID.class), rs.getObject("sucursal_id", UUID.class), rs.getString("nombre"),
                rs.getBigDecimal("costo_envio"), rs.getBigDecimal("pedido_minimo"), tiempo);
    }
}
