package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.events.OutboxRelay;
import com.restaurantes.shared.ids.UuidV7;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Reparto bajo concurrencia real (varias conexiones): lo que dos peticiones simultaneas nunca deben hacer dos veces.
 */
@QuarkusTest
@TestProfile(PoolGrande.class)
class EntregasConcurrenciaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    Escenario escenario;

    @Inject
    OutboxRelay relay;

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

    private void drenar() {
        for (int i = 0; i < 50 && relay.publicarLote() > 0; i++) {
            // sigue
        }
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

    private record Local(Restaurante r, UUID zona, UUID cliente, UUID direccion) {
    }

    private Local local() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.50", "0");
        String cedula = Identificaciones.cedulaAleatoria();
        UUID cliente = UuidV7.next();
        post(escenario.token(r, "cajero"), "/api/v1/delivery/clientes", Map.of("id", cliente.toString(), "nombre", "Ana",
                "tipoIdentificacion", "05", "identificacion", cedula, "telefono", "099"));
        UUID direccion = UuidV7.next();
        post(escenario.token(r, "cajero"), "/api/v1/delivery/clientes/" + cliente + "/direcciones",
                Map.of("id", direccion.toString(), "direccion", "Calle 1"));
        return new Local(r, zona, cliente, direccion);
    }

    /** Pedido a domicilio listo con su entrega pendiente (total 14.00). */
    private UUID pedidoConEntrega(Local l) {
        Restaurante r = l.r();
        UUID pedidoId = UuidV7.next();
        String caja = escenario.token(r, "cajero");
        assertEquals(201, post(caja, "/api/v1/delivery/pedidos", Map.of("id", pedidoId.toString(), "clienteId", l.cliente().toString(),
                "direccionId", l.direccion().toString(), "zonaId", l.zona().toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", 1)))).statusCode());
        post(caja, "/api/v1/pedidos/" + pedidoId + "/confirmar", "{}");
        drenar();
        UUID item = UUID.fromString(escenario.texto(r.tenantId(), "select id::text from pedido_items where pedido_id = ?", pedidoId));
        post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + item + "/listo", "{}");
        drenar();
        assertEquals("pendiente", escenario.texto(r.tenantId(), "select estado from entregas where pedido_id = ?", pedidoId));
        return pedidoId;
    }

    private void asignarYRecoger(Local l, UUID pedidoId, UUID repartidor) {
        post(escenario.token(l.r(), "cajero"), "/api/v1/delivery/entregas/" + pedidoId + "/asignar", Map.of("repartidorId", repartidor.toString()));
        post(escenario.tokenDe(l.r(), repartidor, "repartidor"), "/api/v1/delivery/entregas/" + pedidoId + "/recoger", "{}");
    }

    // ------------------------------------------------------------------ pruebas

    @Test
    void entregarSimultaneamenteElMismoPedidoCobraUnaSolaVez() throws Exception {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        asignarYRecoger(l, pedidoId, repartidor);
        String token = escenario.tokenDe(r, repartidor, "repartidor");

        List<Integer> estados = simultaneamente(6, () ->
                post(token, "/api/v1/delivery/entregas/" + pedidoId + "/entregar", "{}").statusCode());

        assertTrue(estados.stream().allMatch(s -> s == 200), estados.toString());
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pagos where pedido_id = ?", pedidoId), "un solo cobro");
        assertEquals(0, new BigDecimal("14.00").compareTo(
                escenario.sumar(r.tenantId(), "select sum(monto) from pagos where pedido_id = ?", pedidoId)));
        assertEquals("cerrado", escenario.texto(r.tenantId(), "select estado from pedidos where id = ?", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedido_historial where pedido_id = ? and estado_nuevo = 'entregado'", pedidoId));
        assertEquals(0, new BigDecimal("14.00").compareTo(escenario.sumar(r.tenantId(),
                "select efectivo_por_cobrar from entregas where pedido_id = ?", pedidoId)));
    }

    @Test
    void liquidacionesSimultaneasContanCadaEntregaUnaSolaVez() throws Exception {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        String delRepartidor = escenario.tokenDe(r, repartidor, "repartidor");
        for (int i = 0; i < 3; i++) {
            UUID pedidoId = pedidoConEntrega(l);
            asignarYRecoger(l, pedidoId, repartidor);
            assertEquals(200, post(delRepartidor, "/api/v1/delivery/entregas/" + pedidoId + "/entregar", "{}").statusCode());
        }
        String caja = escenario.token(r, "cajero");

        List<JsonNode> resultados = simultaneamente(5, () ->
                json(post(caja, "/api/v1/delivery/liquidaciones", Map.of("repartidorId", repartidor.toString()))));

        BigDecimal efectivo = resultados.stream().map(j -> j.get("efectivo").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
        int entregas = resultados.stream().mapToInt(j -> j.get("entregas").asInt()).sum();
        assertEquals(0, new BigDecimal("42.00").compareTo(efectivo), "3 entregas de 14.00, contadas una sola vez: " + resultados);
        assertEquals(3, entregas);
        assertEquals(3, escenario.contar(r.tenantId(), "select count(*) from entregas where efectivo_liquidado_en is not null"));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from auditoria where accion = 'delivery.liquidar'"),
                "solo la liquidacion que encontro efectivo se audita");
    }

    @Test
    void asignacionesCruzadasDejanUnEstadoCoherente() throws Exception {
        Local l = local();
        Restaurante r = l.r();
        UUID uno = escenario.crearRepartidor(r);
        UUID otro = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        String caja = escenario.token(r, "cajero");

        List<Integer> estados = simultaneamente(6, () -> {
            UUID quien = ThreadLocalRandom.current().nextBoolean() ? uno : otro;
            return post(caja, "/api/v1/delivery/entregas/" + pedidoId + "/asignar", Map.of("repartidorId", quien.toString())).statusCode();
        });

        assertTrue(estados.stream().allMatch(s -> s == 200), estados.toString());
        String asignado = escenario.texto(r.tenantId(), "select repartidor_id::text from entregas where pedido_id = ?", pedidoId);
        assertTrue(asignado.equals(uno.toString()) || asignado.equals(otro.toString()));
        assertEquals("asignado", escenario.texto(r.tenantId(), "select estado from pedidos where id = ?", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedido_historial where pedido_id = ? and estado_nuevo = 'asignado'", pedidoId),
                "el pedido paso a asignado una sola vez");
    }

    @Test
    void recogerYCancelarALaVezNoDejanElPedidoEnCaminoYCancelado() throws Exception {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        post(escenario.token(r, "cajero"), "/api/v1/delivery/entregas/" + pedidoId + "/asignar", Map.of("repartidorId", repartidor.toString()));
        String delRepartidor = escenario.tokenDe(r, repartidor, "repartidor");
        String admin = escenario.token(r, "admin");

        AtomicInteger turno = new AtomicInteger();
        List<Integer> estados = simultaneamente(2, () -> {
            // Una peticion recoge; la otra cancela. Cualquiera de los dos ordenes es valido, pero no ambos resultados a la vez.
            if (turno.getAndIncrement() == 0) {
                return post(delRepartidor, "/api/v1/delivery/entregas/" + pedidoId + "/recoger", "{}").statusCode();
            }
            return post(admin, "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "x")).statusCode();
        });

        drenar();
        String estadoPedido = escenario.texto(r.tenantId(), "select estado from pedidos where id = ?", pedidoId);
        String estadoEntrega = escenario.texto(r.tenantId(), "select estado from entregas where pedido_id = ?", pedidoId);
        assertTrue(estados.stream().allMatch(s -> s == 200 || s == 409), estados.toString());
        // Coherencia: pedido cancelado <=> entrega fallida; si no se cancelo, la entrega sigue en curso.
        if (estadoPedido.equals("cancelado")) {
            assertEquals("fallido", estadoEntrega);
        } else {
            assertTrue(estadoEntrega.equals("asignado") || estadoEntrega.equals("recogido"), estadoPedido + " / " + estadoEntrega);
        }
    }
}
