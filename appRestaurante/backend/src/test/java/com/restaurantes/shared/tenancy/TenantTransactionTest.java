package com.restaurantes.shared.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.restaurantes.shared.ids.UuidV7;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * Verifica contra una base real (esquema v2 aplicado) que {@link TenantTransaction}
 * respeta ADR-002. El pool de pruebas tiene UNA conexion, asi que cualquier filtracion
 * de contexto entre usos aparece de inmediato.
 */
@QuarkusTest
class TenantTransactionTest {

    @Inject
    TenantTransaction tx;

    @Inject
    AgroalDataSource dataSource;

    // ---------------------------------------------------------------- helpers

    private UUID crearTenant() {
        UUID id = UuidV7.next();
        String sufijo = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        tx.runInTenant(id, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into tenants(id, ruc, razon_social, slug, plan_id) "
                            + "select ?, ?, ?, ?, id from planes where codigo = 'basico'")) {
                ps.setObject(1, id);
                ps.setString(2, "179" + sufijo);
                ps.setString(3, "Tenant " + sufijo);
                ps.setString(4, "t-" + sufijo);
                ps.executeUpdate();
            }
        });
        return id;
    }

    private UUID crearSucursal(UUID tenant, String nombre) {
        return tx.withTenant(tenant, c -> insertarSucursal(c, tenant, tenant, nombre));
    }

    /** Inserta una sucursal con el tenant_id indicado (permite probar el rechazo por RLS). */
    private UUID insertarSucursal(Connection c, UUID contexto, UUID tenantFila, String nombre) throws SQLException {
        UUID id = UuidV7.next();
        String estab = String.format("%03d", ThreadLocalRandom.current().nextInt(1, 999));
        try (PreparedStatement ps = c.prepareStatement(
                "insert into sucursales(id, tenant_id, nombre, cod_establecimiento, cod_punto_emision, direccion) "
                        + "values (?, ?, ?, ?, '001', 'Calle 1')")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantFila);
            ps.setString(3, nombre);
            ps.setString(4, estab);
            ps.executeUpdate();
        }
        return id;
    }

    /** Cuenta filas dentro de la transaccion del tenant (devuelve long primitivo: evita sobrecargas ambiguas de assertEquals). */
    private long contarEn(UUID tenant, String sql) {
        Long valor = tx.withTenant(tenant, c -> contar(c, sql));
        return valor;
    }

    private static long contar(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ pruebas

    @Test
    void cadaTenantVeSoloSusDatos() {
        UUID a = crearTenant();
        UUID b = crearTenant();
        crearSucursal(a, "Sucursal A");
        crearSucursal(b, "Sucursal B");

        assertEquals(1L, contarEn(a, "select count(*) from sucursales"));
        assertEquals(1L, contarEn(b, "select count(*) from sucursales"));
        assertEquals(1L, contarEn(a, "select count(*) from tenants"));
        assertEquals(0L, contarEn(b, "select count(*) from sucursales where nombre = 'Sucursal A'"));
    }

    @Test
    void unTenantNoPuedeInsertarFilasDeOtro() {
        UUID a = crearTenant();
        UUID b = crearTenant();

        TenantTransactionException error = assertThrows(TenantTransactionException.class,
                () -> tx.withTenant(b, c -> insertarSucursal(c, b, a, "Intrusa")));
        assertEquals("42501", error.sqlState(), "RLS debe rechazar con insufficient_privilege");
        assertEquals(0L, contarEn(a, "select count(*) from sucursales where nombre = 'Intrusa'"));
    }

    @Test
    void sinContextoLaConsultaFalla() throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("select count(*) from sucursales")) {
            assertThrows(SQLException.class, ps::executeQuery, "sin tenant en contexto debe fallar, no devolver todo");
        }
    }

    @Test
    void elContextoNoSobreviveALaTransaccion() throws SQLException {
        UUID a = crearTenant();
        contarEn(a, "select 1");

        // Pool de una sola conexion: es la misma conexion fisica que uso withTenant.
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("select current_setting('app.tenant_id', true)");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            String valor = rs.getString(1);
            assertNotEquals(a.toString(), valor, "el tenant anterior no debe quedar en la conexion");
            assertTrue(valor == null || valor.isEmpty(), "esperado vacio, fue: " + valor);
        }
    }

    @Test
    void unErrorHaceRollbackDeTodo() {
        UUID a = crearTenant();
        assertThrows(IllegalStateException.class, () -> tx.runInTenant(a, c -> {
            insertarSucursal(c, a, a, "No debe persistir");
            throw new IllegalStateException("fallo de negocio");
        }));
        assertEquals(0L, contarEn(a, "select count(*) from sucursales"));
    }

    @Test
    void laConexionSeDevuelveAlPoolTrasUnError() {
        UUID a = crearTenant();
        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalStateException.class, () -> tx.runInTenant(a, c -> {
                throw new IllegalStateException("x");
            }));
        }
        // Con pool de 1 conexion, si se hubiera filtrado alguna esto se quedaria colgado/fallaria.
        assertEquals(0L, contarEn(a, "select count(*) from sucursales"));
    }

    @Test
    void laBaseAceptaLosUuidV7DeJavaYRechazaOtros() {
        UUID a = crearTenant();
        UUID sucursal = crearSucursal(a, "Matriz");
        LocalDate hoyUtc = LocalDate.now(ZoneOffset.UTC);

        UUID pedido = UuidV7.next();
        tx.runInTenant(a, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into pedidos(id, tenant_id, sucursal_id, canal, fecha_operativa) values (?, ?, ?, 'para_llevar', current_date)")) {
                ps.setObject(1, pedido);
                ps.setObject(2, a);
                ps.setObject(3, sucursal);
                ps.executeUpdate();
            }
        });
        assertEquals(1L, contarEn(a, "select count(*) from pedidos"));

        // La funcion SQL y el codigo Java deben coincidir en la fecha codificada.
        LocalDate fechaSql = tx.withTenant(a, c -> {
            try (PreparedStatement ps = c.prepareStatement("select app.fecha_de_uuid_v7(?)")) {
                ps.setObject(1, pedido);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, LocalDate.class);
                }
            }
        });
        assertEquals(UuidV7.timestamp(pedido).atZone(ZoneOffset.UTC).toLocalDate(), fechaSql);
        assertEquals(hoyUtc, fechaSql);

        // Un UUID v4 debe ser rechazado por el CHECK de la base (23514).
        TenantTransactionException error = assertThrows(TenantTransactionException.class, () -> tx.runInTenant(a, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into pedidos(id, tenant_id, sucursal_id, canal, fecha_operativa) values (?, ?, ?, 'para_llevar', current_date)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, a);
                ps.setObject(3, sucursal);
                ps.executeUpdate();
            }
        }));
        assertEquals("23514", error.sqlState());
    }
}
