package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.events.OutboxRelay;
import com.restaurantes.shared.ids.UuidV7;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Agregar items bajo concurrencia real (varias conexiones): lo que nunca debe quedar incoherente. */
@QuarkusTest
@TestProfile(PoolGrande.class)
class AgregarItemsConcurrenciaTest {

    @Inject
    Escenario escenario;

    @Inject
    OutboxRelay relay;

    // ---------------------------------------------------------------- helpers

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

    private UUID pedidoConfirmado(Restaurante r) {
        UUID pedidoId = UuidV7.next();
        String token = escenario.token(r, "mesero");
        assertEquals(201, post(token, "/api/v1/pedidos", Map.of("id", pedidoId.toString(), "canal", "salon", "mesaId", r.mesaId().toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", 1)))).statusCode());
        post(token, "/api/v1/pedidos/" + pedidoId + "/confirmar", "{}");
        drenar();
        return pedidoId;
    }

    private Response agregar(Restaurante r, UUID pedidoId, UUID itemId, UUID producto) {
        return post(escenario.token(r, "mesero"), "/api/v1/pedidos/" + pedidoId + "/items",
                Map.of("items", List.of(Map.of("id", itemId.toString(), "productoId", producto.toString(), "cantidad", 1))));
    }

    // ------------------------------------------------------------------ pruebas

    @Test
    void laMismaPeticionSimultaneaAgregaUnaSolaVez() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmado(r);
        UUID itemId = UuidV7.next();

        List<Integer> estados = simultaneamente(6, () -> agregar(r, pedidoId, itemId, bebida).statusCode());

        assertEquals(1, estados.stream().filter(s -> s == 201).count(), estados.toString());
        assertEquals(5, estados.stream().filter(s -> s == 200).count(), estados.toString());
        assertEquals(2, escenario.contar(r.tenantId(), "select count(*) from pedido_items where pedido_id = ?", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'ItemsAgregados'"));
        assertEquals("17.25", escenario.texto(r.tenantId(), "select total::text from pedidos where id = ?", pedidoId));
    }

    @Test
    void variosItemsDistintosSimultaneosSumanExactamente() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmado(r);
        int rondas = 6;
        int hilos = 12;

        // Varias rondas: un solo choque entre transacciones sin bloqueo ya dejaria un total que no suma los items.
        for (int ronda = 0; ronda < rondas; ronda++) {
            List<Integer> estados = simultaneamente(hilos, () -> agregar(r, pedidoId, UuidV7.next(), bebida).statusCode());
            assertTrue(estados.stream().allMatch(s -> s == 201), estados.toString());
        }

        int items = 1 + rondas * hilos;
        assertEquals(items, escenario.contar(r.tenantId(), "select count(*) from pedido_items where pedido_id = ?", pedidoId));
        // base = 10 + 5 por bebida; el IVA es 15 % por linea (1.50 + 0.75 por bebida)
        BigDecimal base = new BigDecimal("10.00").add(new BigDecimal("5.00").multiply(BigDecimal.valueOf(rondas * hilos)));
        BigDecimal iva = new BigDecimal("1.50").add(new BigDecimal("0.75").multiply(BigDecimal.valueOf(rondas * hilos)));
        assertEquals(base.toPlainString(), escenario.texto(r.tenantId(), "select subtotal::text from pedidos where id = ?", pedidoId));
        assertEquals(base.add(iva).toPlainString(), escenario.texto(r.tenantId(), "select total::text from pedidos where id = ?", pedidoId));
        drenar();
        assertEquals(items, escenario.contar(r.tenantId(), "select count(*) from pedido_items where pedido_id = ? and estado_cocina = 'enviado'", pedidoId));
    }

    @Test
    void agregarYPagarAlMismoTiempoNuncaDejanUnPedidoPagadoConTotalDistinto() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmado(r);
        AtomicInteger turno = new AtomicInteger();

        List<Integer> estados = simultaneamente(2, () -> {
            if (turno.getAndIncrement() == 0) {
                return agregar(r, pedidoId, UuidV7.next(), bebida).statusCode();
            }
            return post(escenario.token(r, "cajero"), "/api/v1/pagos", Map.of("id", UuidV7.next().toString(),
                    "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("11.50"))).statusCode();
        });

        assertTrue(estados.stream().allMatch(s -> s == 200 || s == 201 || s == 409), estados.toString());
        String estadoPago = escenario.texto(r.tenantId(), "select estado_pago from pedidos where id = ?", pedidoId);
        BigDecimal total = new BigDecimal(escenario.texto(r.tenantId(), "select total::text from pedidos where id = ?", pedidoId));
        BigDecimal cobrado = escenario.sumar(r.tenantId(), "select coalesce(sum(monto), 0) from pagos where pedido_id = ?", pedidoId);
        if (estadoPago.equals("pagado")) {
            assertEquals(0, total.compareTo(cobrado), "pagado debe significar cobrado == total: " + estados);
        } else {
            assertEquals("parcial", estadoPago, estados.toString());
            assertTrue(cobrado.compareTo(total) < 0, estados.toString());
        }
        // Y la factura, si existe, coincide con el total.
        drenar();
        String importe = escenario.texto(r.tenantId(), "select importe_total::text from comprobantes where pedido_id = ? and tipo = '01'", pedidoId);
        if (importe != null) {
            assertEquals(0, total.compareTo(new BigDecimal(importe)), "la factura refleja todos los items");
        }
    }

    @Test
    void agregarYCancelarAlMismoTiempoNoDejanItemsVivosEnUnPedidoCancelado() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmado(r);
        AtomicInteger turno = new AtomicInteger();

        List<Integer> estados = simultaneamente(2, () -> {
            if (turno.getAndIncrement() == 0) {
                return agregar(r, pedidoId, UuidV7.next(), bebida).statusCode();
            }
            return post(escenario.token(r, "admin"), "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "x")).statusCode();
        });

        drenar();
        assertTrue(estados.stream().allMatch(s -> s == 200 || s == 201 || s == 409), estados.toString());
        if (escenario.texto(r.tenantId(), "select estado from pedidos where id = ?", pedidoId).equals("cancelado")) {
            assertEquals(0, escenario.contar(r.tenantId(),
                    "select count(*) from pedido_items where pedido_id = ? and estado_cocina <> 'anulado'", pedidoId),
                    "un pedido cancelado no deja items vivos: " + estados);
        }
    }
}
