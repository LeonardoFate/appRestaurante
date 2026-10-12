package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.ids.UuidV7;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
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
 * Peticiones HTTP realmente simultaneas contra la base real. Aqui se comprueba lo que una prueba secuencial
 * no puede: que el saldo, el estado de cobro y el evento sean correctos cuando varios cajeros cobran a la vez.
 */
@QuarkusTest
@TestProfile(PoolGrande.class)
class ConcurrenciaTest {

    @Inject
    Escenario escenario;

    private static Map<String, Object> pedidoSalon(Restaurante r, UUID id, String cantidad) {
        return Map.of("id", id.toString(), "canal", "salon", "mesaId", r.mesaId().toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal(cantidad))));
    }

    private static int postStatus(String token, String ruta, Map<String, Object> cuerpo) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(cuerpo).when().post(ruta).statusCode();
    }

    /** Lanza {@code n} tareas a la vez (todas esperan a la misma senal) y devuelve sus resultados. */
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

    @Test
    void cobrosSimultaneosNuncaSuperanElTotal() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, postStatus(token, "/api/v1/pedidos", pedidoSalon(r, pedidoId, "2")));   // total 23.00

        // 12 cajeros intentan cobrar 5.00 a la vez: caben 4 (20.00); los otros 8 exceden el saldo.
        List<Integer> estados = simultaneamente(12, () -> postStatus(token, "/api/v1/pagos",
                Map.of("id", UuidV7.next().toString(), "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("5.00"))));

        assertEquals(4, estados.stream().filter(s -> s == 201).count(), "estados: " + estados);
        assertEquals(8, estados.stream().filter(s -> s == 409).count(), "estados: " + estados);
        assertEquals(0, estados.stream().filter(s -> s >= 500).count(), "ninguna peticion debe fallar con 5xx");
        assertEquals(0, new BigDecimal("20.00").compareTo(
                escenario.sumar(r.tenantId(), "select sum(monto) from pagos where pedido_id = ?", pedidoId)));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos where id = ? and estado_pago = 'parcial'", pedidoId));
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'PedidoPagado' and agregado_id = ?", pedidoId));
    }

    @Test
    void cobrosSimultaneosQueSumanElTotalDejanElPedidoPagadoYUnSoloEvento() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, postStatus(token, "/api/v1/pedidos", pedidoSalon(r, pedidoId, "2")));   // total 23.00

        // 5 cobros de 4.60 = 23.00 exactos, todos a la vez.
        List<Integer> estados = simultaneamente(5, () -> postStatus(token, "/api/v1/pagos",
                Map.of("id", UuidV7.next().toString(), "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("4.60"))));

        assertEquals(5, estados.stream().filter(s -> s == 201).count(), "estados: " + estados);
        assertEquals(0, new BigDecimal("23.00").compareTo(
                escenario.sumar(r.tenantId(), "select sum(monto) from pagos where pedido_id = ?", pedidoId)));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos where id = ? and estado_pago = 'pagado'", pedidoId),
                "el estado final debe ser 'pagado', no 'parcial' por haber leido datos viejos");
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'PedidoPagado' and agregado_id = ?", pedidoId),
                "exactamente un evento PedidoPagado");
    }

    @Test
    void elMismoPagoReintentadoALaVezSeRegistraUnaSolaVez() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, postStatus(token, "/api/v1/pedidos", pedidoSalon(r, pedidoId, "2")));
        UUID pagoId = UuidV7.next();

        List<Integer> estados = simultaneamente(8, () -> postStatus(token, "/api/v1/pagos",
                Map.of("id", pagoId.toString(), "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("10.00"))));

        assertEquals(1, estados.stream().filter(s -> s == 201).count(), "estados: " + estados);
        assertEquals(7, estados.stream().filter(s -> s == 200).count(), "estados: " + estados);
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pagos where pedido_id = ?", pedidoId));
    }

    @Test
    void laMismaOrdenDePedidoALaVezCreaUnSoloPedidoYNoDejaHuecos() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "mesero");
        UUID pedidoId = UuidV7.next();

        List<Integer> estados = simultaneamente(8, () -> postStatus(token, "/api/v1/pedidos", pedidoSalon(r, pedidoId, "1")));

        assertEquals(1, estados.stream().filter(s -> s == 201).count(), "estados: " + estados);
        assertEquals(7, estados.stream().filter(s -> s == 200).count(), "estados: " + estados);
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos"));
        // El numero consumido es uno solo: el siguiente pedido es el 2.
        assertEquals(1L, escenario.contar(r.tenantId(), "select ultimo_valor from contadores_pedido"));
    }

    @Test
    void pedidosDistintosALaVezRecibenNumerosDistintosYConsecutivos() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "mesero");

        List<Integer> estados = simultaneamente(10, () -> postStatus(token, "/api/v1/pedidos", pedidoSalon(r, UuidV7.next(), "1")));

        assertEquals(10, estados.stream().filter(s -> s == 201).count(), "estados: " + estados);
        assertEquals(10, escenario.contar(r.tenantId(), "select count(distinct numero) from pedidos"));
        assertEquals(10, escenario.contar(r.tenantId(), "select max(numero) from pedidos"));
        assertEquals(1, escenario.contar(r.tenantId(), "select min(numero) from pedidos"));
    }
}
