package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.ids.UuidV7;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Inicio, renovacion y cierre de sesion contra la base real. El token emitido se usa luego en la API de negocio. */
@QuarkusTest
class AuthFlujoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLAVE = "Cl4ve-de-prueba!";
    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @Inject
    Escenario escenario;

    // ---------------------------------------------------------------- helpers

    private static String email() {
        return "usuario" + CONTADOR.incrementAndGet() + "-" + System.nanoTime() + "@prueba.com";
    }

    private Response login(Map<String, Object> cuerpo) {
        return given().contentType(ContentType.JSON).body(cuerpo).when().post("/api/v1/auth/login");
    }

    private Response login(String email, String password) {
        return login(Map.of("email", email, "password", password));
    }

    private Response renovar(String refreshToken) {
        return given().contentType(ContentType.JSON).body(Map.of("refreshToken", refreshToken)).when().post("/api/v1/auth/refresh");
    }

    private Response cerrarSesion(String refreshToken) {
        return given().contentType(ContentType.JSON).body(Map.of("refreshToken", refreshToken)).when().post("/api/v1/auth/logout");
    }

    private static JsonNode json(Response r) {
        try {
            return JSON.readTree(r.asString());
        } catch (IOException e) {
            throw new IllegalStateException("Respuesta no es JSON: " + r.asString(), e);
        }
    }

    private static JsonNode payload(String jwt) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> roles(JsonNode sesion) {
        Set<String> r = new HashSet<>();
        sesion.get("roles").forEach(n -> r.add(n.asText()));
        return r;
    }

    /** Usuario con una membresia en la sucursal del restaurante. */
    private UUID usuarioConMembresia(Restaurante r, String email, String rol) {
        UUID usuario = escenario.crearUsuario(r, email, CLAVE);
        escenario.darMembresia(r.tenantId(), usuario, r.sucursalId(), rol);
        return usuario;
    }

    private static void assertCodigo(int estado, String codigo, Response r) {
        assertEquals(estado, r.statusCode(), r.asString());
        assertEquals(codigo, json(r).get("codigo").asText());
    }

    // ------------------------------------------------------------------ login

    @Test
    void elLoginEmiteUnTokenQueSirveParaLaApiDeNegocio() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        UUID usuario = usuarioConMembresia(r, email, "mesero");

        Response respuesta = login(email, CLAVE);

        assertEquals(200, respuesta.statusCode(), respuesta.asString());
        assertEquals("no-store", respuesta.header("Cache-Control"), "las credenciales y tokens no se cachean");
        JsonNode sesion = json(respuesta);
        assertEquals("Bearer", sesion.get("tokenType").asText());
        assertEquals(900, sesion.get("expiresIn").asInt());
        assertEquals(r.tenantId().toString(), sesion.get("tenantId").asText());
        assertEquals(r.sucursalId().toString(), sesion.get("sucursalId").asText());
        assertEquals(Set.of("mesero"), roles(sesion));
        assertFalse(sesion.get("refreshToken").asText().isBlank());

        JsonNode claims = payload(sesion.get("accessToken").asText());
        assertEquals("restaurantes", claims.get("iss").asText());
        assertEquals(usuario.toString(), claims.get("sub").asText());
        assertEquals(r.tenantId().toString(), claims.get("tenant_id").asText());
        assertEquals(r.sucursalId().toString(), claims.get("sucursal_id").asText());
        assertEquals("mesero", claims.get("groups").get(0).asText());
        assertEquals(900, claims.get("exp").asLong() - claims.get("iat").asLong());

        // El token recien emitido funciona de verdad en la API de negocio: la identidad del pedido es la del login.
        UUID pedidoId = UuidV7.next();
        Response pedido = given().auth().oauth2(sesion.get("accessToken").asText()).contentType(ContentType.JSON)
                .body(Map.of("id", pedidoId.toString(), "canal", "salon", "mesaId", r.mesaId().toString(),
                        "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("1")))))
                .when().post("/api/v1/pedidos");
        assertEquals(201, pedido.statusCode(), pedido.asString());
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos where id = ? and usuario_id = ?", pedidoId, usuario));
    }

    @Test
    void lasCredencialesInvalidasNoRevelanSiElUsuarioExiste() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        usuarioConMembresia(r, email, "mesero");

        Response claveMala = login(email, "otra-clave");
        Response noExiste = login("nadie-" + email, "otra-clave");

        assertCodigo(401, "CREDENCIALES_INVALIDAS", claveMala);
        assertCodigo(401, "CREDENCIALES_INVALIDAS", noExiste);
        assertEquals(claveMala.asString(), noExiste.asString(), "mismo cuerpo exacto: no se puede distinguir un caso del otro");
    }

    @Test
    void elEmailNoDistingueMayusculasNiEspacios() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        usuarioConMembresia(r, email, "mesero");

        assertEquals(200, login("  " + email.toUpperCase() + " ", CLAVE).statusCode());
    }

    @Test
    void cincoFallosBloqueanLaCuentaYUnLoginCorrectoDespuesDelBloqueoLaLevanta() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        UUID usuario = usuarioConMembresia(r, email, "mesero");

        for (int i = 0; i < 5; i++) {
            assertCodigo(401, "CREDENCIALES_INVALIDAS", login(email, "mala-" + i));
        }
        // Ya bloqueada: ni siquiera la clave correcta entra, y el mensaje es el mismo de siempre.
        assertCodigo(401, "CREDENCIALES_INVALIDAS", login(email, CLAVE));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from usuarios where id = ? and login_bloqueado_hasta > now()", usuario));

        // Se cumple el plazo del bloqueo (se adelanta el reloj en la base).
        escenario.ejecutar(r.tenantId(), "update usuarios set login_bloqueado_hasta = now() - interval '1 minute' where id = ?", usuario);

        assertEquals(200, login(email, CLAVE).statusCode());
        assertEquals(0L, escenario.contar(r.tenantId(), "select login_intentos from usuarios where id = ?", usuario),
                "el login correcto reinicia el contador");
    }

    @Test
    void cuatroFallosNoBloqueanYElLoginCorrectoReiniciaElContador() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        UUID usuario = usuarioConMembresia(r, email, "mesero");

        for (int i = 0; i < 4; i++) {
            login(email, "mala-" + i);
        }
        assertEquals(200, login(email, CLAVE).statusCode());
        assertEquals(0L, escenario.contar(r.tenantId(), "select login_intentos from usuarios where id = ?", usuario));
        // Reiniciado: otros 4 fallos siguen sin bloquear.
        for (int i = 0; i < 4; i++) {
            login(email, "mala-" + i);
        }
        assertEquals(200, login(email, CLAVE).statusCode());
    }

    @Test
    void unUsuarioDesactivadoNoEntra() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        UUID usuario = usuarioConMembresia(r, email, "mesero");
        escenario.ejecutar(r.tenantId(), "update usuarios set activo = false where id = ?", usuario);

        assertCodigo(401, "CREDENCIALES_INVALIDAS", login(email, CLAVE));
    }

    // -------------------------------------------------- restaurante y sucursal

    @Test
    void conVariosRestaurantesHayQueElegirYNoSePuedeEntrarAUnoAjeno() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        String email = email();
        UUID usuario = escenario.crearUsuario(a, email, CLAVE);
        escenario.darMembresia(a.tenantId(), usuario, a.sucursalId(), "mesero");
        escenario.darMembresia(b.tenantId(), usuario, b.sucursalId(), "cajero");

        assertCodigo(422, "TENANT_REQUERIDO", login(email, CLAVE));

        Response enB = login(Map.of("email", email, "password", CLAVE, "tenantId", b.tenantId().toString()));
        assertEquals(200, enB.statusCode(), enB.asString());
        assertEquals(b.tenantId().toString(), json(enB).get("tenantId").asText());
        assertEquals(Set.of("cajero"), roles(json(enB)));

        assertCodigo(403, "SIN_ACCESO", login(Map.of("email", email, "password", CLAVE, "tenantId", UuidV7.next().toString())));
    }

    @Test
    void unaMembresiaDeTodasLasSucursalesExigeElegirYLaSucursalDebeSerDelRestaurante() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        UUID otraSucursal = escenario.crearSucursal(a.tenantId(), "002");
        String email = email();
        UUID usuario = escenario.crearUsuario(a, email, CLAVE);
        escenario.darMembresia(a.tenantId(), usuario, null, "admin");

        assertCodigo(422, "SUCURSAL_REQUERIDA", login(email, CLAVE));

        Response ok = login(Map.of("email", email, "password", CLAVE, "sucursalId", otraSucursal.toString()));
        assertEquals(200, ok.statusCode(), ok.asString());
        assertEquals(otraSucursal.toString(), json(ok).get("sucursalId").asText());

        // Una sucursal de OTRO restaurante no sirve (la base la oculta: RLS).
        assertCodigo(403, "SIN_ACCESO", login(Map.of("email", email, "password", CLAVE, "sucursalId", b.sucursalId().toString())));
        // Ni una que no existe.
        assertCodigo(403, "SIN_ACCESO", login(Map.of("email", email, "password", CLAVE, "sucursalId", UuidV7.next().toString())));
    }

    @Test
    void losRolesSonLaUnionDeLasMembresiasQueAplicanALaSucursal() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        UUID usuario = escenario.crearUsuario(r, email, CLAVE);
        escenario.darMembresia(r.tenantId(), usuario, r.sucursalId(), "cajero");
        escenario.darMembresia(r.tenantId(), usuario, null, "mesero");

        Response respuesta = login(Map.of("email", email, "password", CLAVE, "sucursalId", r.sucursalId().toString()));

        assertEquals(200, respuesta.statusCode(), respuesta.asString());
        assertEquals(Set.of("cajero", "mesero"), roles(json(respuesta)));
    }

    @Test
    void unRestauranteSuspendidoNoPermiteEntrar() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        usuarioConMembresia(r, email, "mesero");
        escenario.ejecutar(r.tenantId(), "update tenants set estado = 'suspendido' where id = ?", r.tenantId());

        assertCodigo(403, "RESTAURANTE_INACTIVO", login(email, CLAVE));
    }

    @Test
    void unaMembresiaRevocadaNoDaAcceso() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        UUID usuario = usuarioConMembresia(r, email, "mesero");
        escenario.ejecutar(r.tenantId(), "update membresias set estado = 'revocada', revocada_en = now() where usuario_id = ?", usuario);

        assertCodigo(403, "SIN_ACCESO", login(email, CLAVE));
    }

    // ----------------------------------------------------------------- refresh

    @Test
    void renovarEntregaTokensNuevosYElRefreshAnteriorYaNoSirve() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        usuarioConMembresia(r, email, "mesero");
        JsonNode inicial = json(login(email, CLAVE));
        String refresh1 = inicial.get("refreshToken").asText();

        Response renovada = renovar(refresh1);

        assertEquals(200, renovada.statusCode(), renovada.asString());
        assertEquals("no-store", renovada.header("Cache-Control"));
        JsonNode sesion2 = json(renovada);
        String refresh2 = sesion2.get("refreshToken").asText();
        assertNotEquals(refresh1, refresh2, "el refresh token rota en cada uso");
        assertEquals(Set.of("mesero"), roles(sesion2));
        // El token nuevo funciona en la API.
        assertEquals(404, given().auth().oauth2(sesion2.get("accessToken").asText())
                .when().get("/api/v1/pedidos/" + UuidV7.next()).statusCode(), "autenticado: llega al endpoint y no encuentra el pedido");
        // Y se puede seguir renovando con el nuevo.
        assertEquals(200, renovar(refresh2).statusCode());
    }

    @Test
    void reutilizarUnRefreshTokenYaUsadoCierraTodaLaSesion() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        usuarioConMembresia(r, email, "mesero");
        String refresh1 = json(login(email, CLAVE)).get("refreshToken").asText();
        String refresh2 = json(renovar(refresh1)).get("refreshToken").asText();

        // Alguien presenta de nuevo el primero (se lo copio): se rechaza...
        assertCodigo(401, "TOKEN_INVALIDO", renovar(refresh1));
        // ...y ademas queda invalidado el que el usuario legitimo acababa de recibir: toda la familia.
        assertCodigo(401, "TOKEN_INVALIDO", renovar(refresh2));
        assertEquals(0L, escenario.contar(r.tenantId(), "select count(*) from refresh_tokens where revocado_en is null"));
    }

    @Test
    void cerrarSesionRevocaElRefreshTokenYEsIdempotente() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        usuarioConMembresia(r, email, "mesero");
        String refresh = json(login(email, CLAVE)).get("refreshToken").asText();

        assertEquals(204, cerrarSesion(refresh).statusCode());
        assertCodigo(401, "TOKEN_INVALIDO", renovar(refresh));
        assertEquals(204, cerrarSesion(refresh).statusCode(), "cerrar dos veces no es un error");
        assertEquals(204, cerrarSesion("basura").statusCode(), "un token que no existe tampoco");
        assertEquals(204, cerrarSesion(UuidV7.next() + ".abc").statusCode());
    }

    @Test
    void unRefreshTokenExpiradoNoSirve() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        usuarioConMembresia(r, email, "mesero");
        String refresh = json(login(email, CLAVE)).get("refreshToken").asText();
        escenario.ejecutar(r.tenantId(), "update refresh_tokens set expira_en = now() - interval '1 minute'");

        assertCodigo(401, "TOKEN_INVALIDO", renovar(refresh));
    }

    @Test
    void siSeRevocaLaMembresiaDespuesDelLoginLaRenovacionFalla() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        UUID usuario = usuarioConMembresia(r, email, "mesero");
        String refresh = json(login(email, CLAVE)).get("refreshToken").asText();
        escenario.ejecutar(r.tenantId(), "update membresias set estado = 'revocada', revocada_en = now() where usuario_id = ?", usuario);

        assertCodigo(401, "TOKEN_INVALIDO", renovar(refresh));
        assertEquals(0L, escenario.contar(r.tenantId(), "select count(*) from refresh_tokens where revocado_en is null"),
                "la sesion queda cerrada, no solo rechazada una vez");
    }

    @Test
    void unRefreshTokenMalFormadoOAjenoNoSirve() {
        assertCodigo(401, "TOKEN_INVALIDO", renovar("basura"));
        assertCodigo(401, "TOKEN_INVALIDO", renovar("no-es-uuid.abc"));
        assertCodigo(401, "TOKEN_INVALIDO", renovar(UuidV7.next() + ".secreto-inventado"));
        assertCodigo(401, "TOKEN_INVALIDO", renovar(UuidV7.next() + "."));
    }

    @Test
    void unRefreshTokenNoSirveAnteOtroRestaurante() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        String email = email();
        usuarioConMembresia(a, email, "mesero");
        String refresh = json(login(email, CLAVE)).get("refreshToken").asText();
        String secreto = refresh.substring(refresh.indexOf('.') + 1);

        // Mismo secreto pero presentado como si fuera del restaurante B: la base no lo ve (RLS) y no coincide el hash.
        assertCodigo(401, "TOKEN_INVALIDO", renovar(b.tenantId() + "." + secreto));
    }

    // ------------------------------------------------------ almacenamiento

    @Test
    void nadaSensibleSeGuardaEnClaro() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = email();
        UUID usuario = usuarioConMembresia(r, email, "mesero");
        String refresh = json(login(email, CLAVE)).get("refreshToken").asText();

        // La contrasena esta guardada como Argon2id, nunca en claro.
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from usuarios where id = ? and password_hash like '$argon2id$%'", usuario));
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from usuarios where id = ? and password_hash like ?", usuario, "%" + CLAVE + "%"));
        // Del refresh token solo se guarda un hash: el token entregado no aparece en la tabla.
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from refresh_tokens where token_hash = ?", refresh));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from refresh_tokens where token_hash ~ '^[0-9a-f]{64}$'"));
    }

    // ----------------------------------------------------------- validacion

    @Test
    void validaElFormatoDeLasPeticiones() {
        assertEquals(400, login(new HashMap<>(Map.of("password", CLAVE))).statusCode(), "sin email");
        assertEquals(400, login(Map.of("email", "a@b.com", "password", "")).statusCode(), "clave vacia");
        assertEquals(400, login(Map.of("email", "a@b.com", "password", "x".repeat(129))).statusCode(), "clave demasiado larga");
        assertEquals(400, given().contentType(ContentType.JSON).body(Map.of()).when().post("/api/v1/auth/refresh").statusCode());
    }

    @Test
    void losEndpointsDeAuthSonPublicosYLosDeNegocioSiguenProtegidos() {
        assertEquals(401, login("nadie@prueba.com", "x").statusCode(), "responde 401 de credenciales, no por falta de token");
        assertEquals(401, given().when().get("/api/v1/pedidos/" + UuidV7.next()).statusCode());
    }
}
