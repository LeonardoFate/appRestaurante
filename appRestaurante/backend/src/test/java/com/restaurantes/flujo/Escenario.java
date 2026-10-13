package com.restaurantes.flujo;

import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.tenancy.TenantTransaction;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Datos de prueba: un restaurante completo (tenant, sucursal, mesa, producto, usuario) y sus JWT. */
@ApplicationScoped
public class Escenario {

    public record Restaurante(UUID tenantId, UUID sucursalId, UUID mesaId, UUID productoId, UUID usuarioId) {
    }

    @Inject
    TenantTransaction tx;

    @Inject
    com.restaurantes.auth.internal.HashParaPruebas hashes;

    /** Crea un usuario con contrasena real (Argon2id) y sin membresias; usa el tenant de {@code contexto} solo para abrir la transaccion. */
    public UUID crearUsuario(Restaurante contexto, String email, String password) {
        return tx.withTenant(contexto.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("select app.crear_usuario(?, 'Usuario de prueba', ?)")) {
                ps.setString(1, email);
                ps.setString(2, hashes.hash(password));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            }
        });
    }

    /** Da una membresia activa. {@code sucursalId} null = todas las sucursales del restaurante. */
    public void darMembresia(UUID tenantId, UUID usuarioId, UUID sucursalId, String rol) {
        ejecutar(tenantId, "insert into membresias(tenant_id, usuario_id, sucursal_id, rol) values (?, ?, ?, ?)",
                tenantId, usuarioId, sucursalId, rol);
    }

    /** Crea un usuario con membresia activa de {@code repartidor} en todas las sucursales y devuelve su id. */
    public UUID crearRepartidor(Restaurante r) {
        UUID usuario = crearUsuario(r, "repartidor-" + UuidV7.next() + "@prueba.com", "clave-larga-123");
        darMembresia(r.tenantId(), usuario, null, "repartidor");
        return usuario;
    }

    /** JWT de OTRO usuario del mismo restaurante (por ejemplo un repartidor) en la sucursal del restaurante. */
    public String tokenDe(Restaurante r, UUID usuarioId, String... roles) {
        return Jwt.issuer("restaurantes")
                .subject(usuarioId.toString())
                .groups(Set.of(roles))
                .claim("tenant_id", r.tenantId().toString())
                .claim("sucursal_id", r.sucursalId().toString())
                .expiresIn(Duration.ofMinutes(5))
                .sign();
    }

    /** Crea otra sucursal del mismo restaurante y devuelve su id. */
    public UUID crearSucursal(UUID tenantId, String codigoEstablecimiento) {
        UUID id = UuidV7.next();
        ejecutar(tenantId, "insert into sucursales(id, tenant_id, nombre, cod_establecimiento, cod_punto_emision, direccion) "
                + "values (?, ?, 'Otra', ?, '001', 'Calle 2')", id, tenantId, codigoEstablecimiento);
        return id;
    }

    /** Crea una zona de delivery activa (poligono vacio: aun no se evalua) y devuelve su id. */
    public UUID crearZona(UUID tenantId, UUID sucursalId, String nombre, String costoEnvio, String pedidoMinimo) {
        UUID id = UuidV7.next();
        ejecutar(tenantId, "insert into zonas_delivery(id, tenant_id, sucursal_id, nombre, poligono, costo_envio, pedido_minimo) "
                + "values (?, ?, ?, ?, '{}'::jsonb, ?, ?)", id, tenantId, sucursalId, nombre,
                new BigDecimal(costoEnvio), new BigDecimal(pedidoMinimo));
        return id;
    }

    /** Crea una estacion de cocina activa en la sucursal y devuelve su id. */
    public UUID crearEstacion(UUID tenantId, UUID sucursalId, String nombre) {
        UUID id = UuidV7.next();
        ejecutar(tenantId, "insert into estaciones_cocina(id, tenant_id, sucursal_id, nombre) values (?, ?, ?, ?)",
                id, tenantId, sucursalId, nombre);
        return id;
    }

    /** Asigna una estacion a un producto en una sucursal (productos_sucursal). Debe hacerse ANTES de crear el pedido. */
    public void asignarEstacion(UUID tenantId, UUID productoId, UUID sucursalId, UUID estacionId) {
        ejecutar(tenantId, "insert into productos_sucursal(tenant_id, producto_id, sucursal_id, estacion_id) values (?, ?, ?, ?)",
                tenantId, productoId, sucursalId, estacionId);
    }

    /** Crea otro producto del restaurante (IVA 15 %) y devuelve su id. */
    public UUID crearProducto(UUID tenantId, String nombre, String precio) {
        UUID id = UuidV7.next();
        tx.runInTenant(tenantId, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    insert into productos(id, tenant_id, categoria_id, nombre, precio_base, tarifa_iva_id)
                    values (?, ?, (select id from categorias where tenant_id = ? limit 1), ?, ?,
                            (select id from tarifas_iva where tenant_id is null and codigo_sri = '4'
                              order by vigente_desde desc limit 1))
                    """)) {
                ps.setObject(1, id);
                ps.setObject(2, tenantId);
                ps.setObject(3, tenantId);
                ps.setString(4, nombre);
                ps.setBigDecimal(5, new BigDecimal(precio));
                ps.executeUpdate();
            }
        });
        return id;
    }

    /** Igual que {@link #token} pero para trabajar en otra sucursal del mismo restaurante. */
    public String tokenEnSucursal(Restaurante r, UUID sucursalId, String... roles) {
        return Jwt.issuer("restaurantes")
                .subject(r.usuarioId().toString())
                .groups(Set.of(roles))
                .claim("tenant_id", r.tenantId().toString())
                .claim("sucursal_id", sucursalId.toString())
                .expiresIn(Duration.ofMinutes(5))
                .sign();
    }

    /** Crea un restaurante con un producto que cuesta {@code precio} antes de IVA (IVA 15 %). */
    public Restaurante crearRestaurante(String precio) {
        UUID tenant = UuidV7.next();
        String sufijo = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        return tx.withTenant(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into tenants(id, ruc, razon_social, slug, plan_id) "
                            + "select ?, ?, ?, ?, id from planes where codigo = 'basico'")) {
                ps.setObject(1, tenant);
                ps.setString(2, "179" + sufijo);
                ps.setString(3, "Tenant " + sufijo);
                ps.setString(4, "t-" + sufijo);
                ps.executeUpdate();
            }
            UUID usuario;
            try (PreparedStatement ps = c.prepareStatement("select app.crear_usuario(?, 'Mesero de prueba', 'hash')")) {
                ps.setString(1, "mesero-" + sufijo + "@prueba.com");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    usuario = rs.getObject(1, UUID.class);
                }
            }
            UUID sucursal = UuidV7.next();
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into sucursales(id, tenant_id, nombre, cod_establecimiento, cod_punto_emision, direccion) "
                            + "values (?, ?, 'Matriz', '001', '001', 'Calle 1')")) {
                ps.setObject(1, sucursal);
                ps.setObject(2, tenant);
                ps.executeUpdate();
            }
            UUID mesa = UuidV7.next();
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into mesas(id, tenant_id, sucursal_id, codigo) values (?, ?, ?, 'M1')")) {
                ps.setObject(1, mesa);
                ps.setObject(2, tenant);
                ps.setObject(3, sucursal);
                ps.executeUpdate();
            }
            UUID categoria = UuidV7.next();
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into categorias(id, tenant_id, nombre) values (?, ?, 'Platos')")) {
                ps.setObject(1, categoria);
                ps.setObject(2, tenant);
                ps.executeUpdate();
            }
            UUID producto = UuidV7.next();
            try (PreparedStatement ps = c.prepareStatement("""
                    insert into productos(id, tenant_id, categoria_id, nombre, precio_base, tarifa_iva_id)
                    values (?, ?, ?, 'Encebollado', ?, (select id from tarifas_iva
                                                         where tenant_id is null and codigo_sri = '4'
                                                         order by vigente_desde desc limit 1))
                    """)) {
                ps.setObject(1, producto);
                ps.setObject(2, tenant);
                ps.setObject(3, categoria);
                ps.setBigDecimal(4, new BigDecimal(precio));
                ps.executeUpdate();
            }
            return new Restaurante(tenant, sucursal, mesa, producto, usuario);
        });
    }

    /** JWT firmado con la clave de pruebas, con las mismas claims que emitira el modulo auth. */
    public String token(Restaurante r, String... roles) {
        return Jwt.issuer("restaurantes")
                .subject(r.usuarioId().toString())
                .groups(Set.of(roles))
                .claim("tenant_id", r.tenantId().toString())
                .claim("sucursal_id", r.sucursalId().toString())
                .expiresIn(Duration.ofMinutes(5))
                .sign();
    }

    /** Cuenta filas con SQL libre, dentro del tenant indicado (respeta RLS). */
    public long contar(UUID tenant, String sql, Object... args) {
        return tx.withTenant(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** Primer valor de la primera fila como texto (null si no hay filas o el valor es NULL). */
    public String texto(UUID tenant, String sql, Object... args) {
        return tx.withTenant(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public BigDecimal sumar(UUID tenant, String sql, Object... args) {
        return tx.withTenant(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBigDecimal(1);
                }
            }
        });
    }

    public void ejecutar(UUID tenant, String sql, Object... args) {
        tx.runInTenant(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
        });
    }
}
