package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.flujo.Escenario.Restaurante;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Sesiones y bloqueos con peticiones realmente simultaneas (varias conexiones a la base). */
@QuarkusTest
@TestProfile(PoolGrande.class)
class AuthConcurrenciaTest {

    private static final String CLAVE = "Cl4ve-de-prueba!";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    Escenario escenario;

    private static <T> List<T> simultaneamente(int n, Callable<T> tarea) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch listos = new CountDownLatch(n);
        CountDownLatch ya = new CountDownLatch(1);
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    ya.await();
                    return tarea.call();
                }));
            }
            listos.await();
            ya.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> f : futuros) {
                resultados.add(f.get());
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID usuario(Restaurante r, String email) {
        UUID u = escenario.crearUsuario(r, email, CLAVE);
        escenario.darMembresia(r.tenantId(), u, r.sucursalId(), "mesero");
        return u;
    }

    private static Response login(String email, String password) {
        return given().contentType(ContentType.JSON).body(Map.of("email", email, "password", password)).when().post("/api/v1/auth/login");
    }

    private static int renovar(String refreshToken) {
        return given().contentType(ContentType.JSON).body(Map.of("refreshToken", refreshToken)).when()
                .post("/api/v1/auth/refresh").statusCode();
    }

    @Test
    void elMismoRefreshTokenUsadoALaVezSoloRenuevaUnaVezYCierraLaSesion() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = "conc-refresh-" + System.nanoTime() + "@prueba.com";
        usuario(r, email);
        String refresh = JSON.readTree(login(email, CLAVE).asString()).get("refreshToken").asText();

        List<Integer> estados = simultaneamente(8, () -> renovar(refresh));

        assertEquals(1, estados.stream().filter(s -> s == 200).count(), "estados: " + estados);
        assertEquals(7, estados.stream().filter(s -> s == 401).count(), "estados: " + estados);
        assertEquals(0, estados.stream().filter(s -> s >= 500).count(), "ninguna peticion debe fallar con 5xx");
        // Los 7 intentos posteriores se tratan como reutilizacion: la sesion entera queda cerrada.
        assertEquals(0L, escenario.contar(r.tenantId(), "select count(*) from refresh_tokens where revocado_en is null"));
    }

    @Test
    void variosFallosALaVezBloqueanLaCuentaSinErrores() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = "conc-bloqueo-" + System.nanoTime() + "@prueba.com";
        UUID usuario = usuario(r, email);

        List<Integer> estados = simultaneamente(8, () -> login(email, "clave-mala").statusCode());

        assertEquals(8, estados.stream().filter(s -> s == 401).count(), "estados: " + estados);
        assertTrue(escenario.contar(r.tenantId(), "select login_intentos from usuarios where id = ?", usuario) >= 5,
                "los intentos concurrentes se cuentan todos (el contador es atomico)");
        assertEquals(401, login(email, CLAVE).statusCode(), "la cuenta quedo bloqueada: ni la clave correcta entra");
    }

    @Test
    void variosLoginsCorrectosALaVezCreanSesionesIndependientes() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String email = "conc-login-" + System.nanoTime() + "@prueba.com";
        usuario(r, email);

        List<String> refrescos = simultaneamente(10, () -> {
            Response respuesta = login(email, CLAVE);
            assertEquals(200, respuesta.statusCode(), respuesta.asString());
            return JSON.readTree(respuesta.asString()).get("refreshToken").asText();
        });

        Set<String> distintos = new HashSet<>(refrescos);
        assertEquals(10, distintos.size(), "cada sesion tiene su propio refresh token");
        assertEquals(10L, escenario.contar(r.tenantId(), "select count(distinct familia_id) from refresh_tokens"),
                "y su propia familia");
    }
}
