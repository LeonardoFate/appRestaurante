package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.events.OutboxRelay;
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

/** Varios usuarios a la vez sobre el mismo pedido: el estado final y los eventos deben ser los correctos. */
@QuarkusTest
@TestProfile(PoolGrande.class)
class CocinaConcurrenciaTest {

    @Inject
    Escenario escenario;

    @Inject
    OutboxRelay relay;

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

    private static int post(String token, String ruta, Object cuerpo) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(cuerpo).when().post(ruta).statusCode();
    }

    private void drenar() {
        for (int i = 0; i < 50 && relay.publicarLote() > 0; i++) {
            // sigue
        }
    }

    private UUID pedidoConItems(Restaurante r, String token, int items) {
        List<Map<String, Object>> lineas = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            lineas.add(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("1")));
        }
        UUID pedidoId = UuidV7.next();
        assertEquals(201, post(token, "/api/v1/pedidos", Map.of("id", pedidoId.toString(), "canal", "salon",
                "mesaId", r.mesaId().toString(), "items", lineas)));
        return pedidoId;
    }

    private String historial(Restaurante r, UUID pedidoId) {
        return escenario.texto(r.tenantId(), """
                select string_agg(coalesce(estado_anterior, '-') || '>' || estado_nuevo, ',' order by ocurrido_en)
                  from pedido_historial where pedido_id = ?
                """, pedidoId);
    }

    @Test
    void seisCocinerosMarcandoItemsALaVezDejanElPedidoListoUnaSolaVez() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = pedidoConItems(r, mesero, 6);
        assertEquals(200, given().auth().oauth2(mesero).contentType(ContentType.JSON).body("{}").when()
                .post("/api/v1/pedidos/" + pedidoId + "/confirmar").statusCode());
        drenar();
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            items.add(escenario.texto(r.tenantId(), "select id::text from (select id, row_number() over (order by id) n "
                    + "from pedido_items where pedido_id = ?) x where n = ?", pedidoId, i));
        }

        List<Integer> estados = simultaneamente(6, new Callable<Integer>() {
            private int siguiente = 0;

            @Override
            public Integer call() {
                String item;
                synchronized (this) {
                    item = items.get(siguiente++);
                }
                return post(cocina, "/api/v1/cocina/items/" + item + "/listo", Map.of());
            }
        });

        assertEquals(6, estados.stream().filter(s -> s == 200).count(), "estados: " + estados);
        assertEquals("listo", escenario.texto(r.tenantId(), "select estado from pedidos where id = ?", pedidoId));
        assertEquals(6, escenario.contar(r.tenantId(), "select count(*) from pedido_items where pedido_id = ? and estado_cocina = 'listo'", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'PedidoListo' and agregado_id = ?", pedidoId),
                "un solo aviso de pedido listo");
        assertEquals("->creado,creado>confirmado,confirmado>en_preparacion,en_preparacion>listo", historial(r, pedidoId),
                "cada transicion ocurre exactamente una vez, sin duplicados ni saltos");
    }

    @Test
    void elMismoItemMarcadoListoPorVariosALaVezSeAplicaUnaVez() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = pedidoConItems(r, mesero, 1);
        post(mesero, "/api/v1/pedidos/" + pedidoId + "/confirmar", Map.of());
        drenar();
        String item = escenario.texto(r.tenantId(), "select id::text from pedido_items where pedido_id = ?", pedidoId);

        List<Integer> estados = simultaneamente(8, () -> post(cocina, "/api/v1/cocina/items/" + item + "/listo", Map.of()));

        assertEquals(8, estados.stream().filter(s -> s == 200).count(), "es idempotente: todos responden bien. Estados: " + estados);
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'PedidoListo' and agregado_id = ?", pedidoId));
        assertEquals("->creado,creado>confirmado,confirmado>en_preparacion,en_preparacion>listo", historial(r, pedidoId));
    }

    @Test
    void confirmarElMismoPedidoVariasVecesALaVezGeneraUnSoloEvento() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String mesero = escenario.token(r, "mesero");
        UUID pedidoId = pedidoConItems(r, mesero, 2);

        List<Integer> estados = simultaneamente(8, () -> post(mesero, "/api/v1/pedidos/" + pedidoId + "/confirmar", Map.of()));

        assertEquals(8, estados.stream().filter(s -> s == 200).count(), "estados: " + estados);
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'PedidoConfirmado' and agregado_id = ?", pedidoId));
        assertEquals("->creado,creado>confirmado", historial(r, pedidoId));
    }

    @Test
    void cancelarYServirALaVezNuncaDejanUnEstadoIncoherente() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        String mesero = escenario.token(r, "mesero");
        String cajero = escenario.token(r, "cajero");
        UUID pedidoId = pedidoConItems(r, mesero, 1);
        post(mesero, "/api/v1/pedidos/" + pedidoId + "/confirmar", Map.of());
        drenar();
        String item = escenario.texto(r.tenantId(), "select id::text from pedido_items where pedido_id = ?", pedidoId);
        post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + item + "/listo", Map.of());

        // Uno cancela el pedido mientras otro sirve el item.
        List<Integer> estados = simultaneamente(2, new Callable<Integer>() {
            private int turno = 0;

            @Override
            public Integer call() {
                int mio;
                synchronized (this) {
                    mio = turno++;
                }
                return mio == 0
                        ? post(cajero, "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "Prueba de carrera"))
                        : post(mesero, "/api/v1/cocina/items/" + item + "/servido", Map.of());
            }
        });

        UUID itemId = UUID.fromString(item);
        String estado = escenario.texto(r.tenantId(), "select estado from pedidos where id = ?", pedidoId);
        String itemEstado = escenario.texto(r.tenantId(), "select estado_cocina from pedido_items where id = ?", itemId);
        assertEquals(0, estados.stream().filter(s -> s >= 500).count(), "ninguna peticion falla con 5xx: " + estados);
        // O gano el servido (entregado, y el cancelar se rechaza) o gano el cancelar (cancelado, y servir se rechaza).
        if (estado.equals("cancelado")) {
            drenar();
            assertEquals("anulado", escenario.texto(r.tenantId(), "select estado_cocina from pedido_items where id = ?", itemId));
        } else {
            assertEquals("entregado", estado);
            assertEquals("servido", itemEstado);
            assertEquals(1, estados.stream().filter(s -> s == 409).count(), "el cancelar perdio la carrera: " + estados);
        }
    }
}
