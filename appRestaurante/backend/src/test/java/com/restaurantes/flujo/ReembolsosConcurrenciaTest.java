package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.events.OutboxRelay;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.jobs.AdminScheduler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Devoluciones de extremo a extremo: reembolso por HTTP -> contabilidad neta, estados, auditoria y evento -> relay ->
 * nota de credito. Contra la base real, con JWT firmados.
 */
@QuarkusTest
@TestProfile(PoolGrande.class)
class ReembolsosConcurrenciaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    Escenario escenario;

    @Inject
    OutboxRelay relay;

    @Inject
    AdminScheduler admin;

    // ---------------------------------------------------------------- helpers

    private static JsonNode json(Response r) {
        try {
            return JSON.readTree(r.asString());
        } catch (IOException e) {
            throw new IllegalStateException("Respuesta no es JSON: " + r.asString(), e);
        }
    }

    private Response post(String token, String ruta, Object cuerpo) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(cuerpo).when().post(ruta);
    }

    private Response post(String token, String ruta) {
        return post(token, ruta, "{}");
    }

    private void drenar() {
        for (int i = 0; i < 50 && relay.publicarLote() > 0; i++) {
            // sigue
        }
    }

    private static void assertCodigo(int estado, String codigo, Response r) {
        assertEquals(estado, r.statusCode(), r.asString());
        assertEquals(codigo, json(r).get("codigo").asText());
    }

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

    /** Pedido de 1 unidad (10.00 + IVA 15 % = 11.50) creado y confirmado. Devuelve su id. */
    private UUID pedidoConfirmado(Restaurante r) {
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        Response creado = post(token, "/api/v1/pedidos", Map.of("id", pedidoId.toString(), "canal", "salon",
                "mesaId", r.mesaId().toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("1")))));
        assertEquals(201, creado.statusCode(), creado.asString());
        assertEquals(200, post(token, "/api/v1/pedidos/" + pedidoId + "/confirmar").statusCode());
        return pedidoId;
    }

    /** Registra un pago en efectivo; devuelve el id del pago. */
    private UUID pagar(Restaurante r, UUID pedidoId, String monto) {
        Response pago = post(escenario.token(r, "cajero"), "/api/v1/pagos", Map.of("id", UuidV7.next().toString(),
                "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal(monto)));
        assertEquals(201, pago.statusCode(), pago.asString());
        return UUID.fromString(json(pago).get("pagoId").asText());
    }

    private Response reembolsar(Restaurante r, String rol, UUID pagoId, String monto, String motivo) {
        return reembolsar(r, rol, UuidV7.next(), pagoId, monto, motivo);
    }

    private Response reembolsar(Restaurante r, String rol, UUID reembolsoId, UUID pagoId, String monto, String motivo) {
        return post(escenario.token(r, rol), "/api/v1/pagos/" + pagoId + "/reembolsos",
                Map.of("id", reembolsoId.toString(), "monto", new BigDecimal(monto), "motivo", motivo));
    }

    private String estadoPedido(Restaurante r, UUID pedidoId) {
        return escenario.texto(r.tenantId(), "select estado from pedidos where id = ?", pedidoId);
    }

    private String estadoPagoPedido(Restaurante r, UUID pedidoId) {
        return escenario.texto(r.tenantId(), "select estado_pago from pedidos where id = ?", pedidoId);
    }

    /** Pedido entregado (todo servido) y pagado por completo. Devuelve {pedidoId, pagoId}. */
    private UUID[] pedidoEntregadoYPagado(Restaurante r) {
        UUID pedidoId = pedidoConfirmado(r);
        drenar();
        UUID item = UUID.fromString(escenario.texto(r.tenantId(), "select id::text from pedido_items where pedido_id = ?", pedidoId));
        String cocina = escenario.token(r, "cocina");
        post(cocina, "/api/v1/cocina/items/" + item + "/listo");
        post(escenario.token(r, "mesero"), "/api/v1/cocina/items/" + item + "/servido");
        assertEquals("entregado", estadoPedido(r, pedidoId));
        UUID pagoId = pagar(r, pedidoId, "11.50");
        assertEquals("cerrado", estadoPedido(r, pedidoId));
        return new UUID[] {pedidoId, pagoId};
    }

    private String notaCredito(Restaurante r, UUID pedidoId, String columna) {
        return escenario.texto(r.tenantId(), "select " + columna + " from comprobantes where pedido_id = ? and tipo = '04'", pedidoId);
    }

    // ------------------------------------------------------------------ pruebas

    @Test
    void reembolsosSimultaneosNuncaDevuelvenMasDeLoCobrado() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoConfirmado(r);
        UUID pagoId = pagar(r, pedidoId, "11.50");

        // 8 reembolsos de 2.00 a la vez sobre 11.50: solo caben 5 (10.00); el resto debe rechazarse.
        List<Integer> estados = simultaneamente(8, () -> reembolsar(r, "admin", pagoId, "2.00", "x").statusCode());

        long creados = estados.stream().filter(s -> s == 201).count();
        assertEquals(5, creados, estados.toString());
        assertTrue(estados.stream().allMatch(s -> s == 201 || s == 409), estados.toString());
        assertEquals(0, new BigDecimal("10.00").compareTo(
                escenario.sumar(r.tenantId(), "select sum(monto) from reembolsos where pago_id = ?", pagoId)));
        assertEquals("reembolsado_parcial", estadoPagoPedido(r, pedidoId));
        assertEquals(5, escenario.contar(r.tenantId(), "select count(*) from auditoria where accion = 'pago.reembolsar'"));
    }

    @Test
    void relaysSimultaneosGeneranUnaSolaNotaDeCredito() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID[] ids = pedidoEntregadoYPagado(r);
        drenar();
        reembolsar(r, "admin", ids[1], "11.50", "x");

        simultaneamente(5, () -> {
            int total = 0;
            int n;
            while ((n = relay.publicarLote()) > 0) {
                total += n;
            }
            return total;
        });

        assertEquals("1", notaCredito(r, ids[0], "count(*)"));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from jobs where payload->>'tipo' = '04'"));
    }
}
