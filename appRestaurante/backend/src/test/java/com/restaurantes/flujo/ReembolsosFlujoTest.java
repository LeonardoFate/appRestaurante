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
class ReembolsosFlujoTest {

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
    void reembolsoParcialTrasLaEntregaDejaElPedidoDevueltoParcialYSinNotaDeCredito() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID[] ids = pedidoEntregadoYPagado(r);
        drenar();   // factura

        Response res = reembolsar(r, "admin", ids[1], "4.00", "plato frio");

        assertEquals(201, res.statusCode(), res.asString());
        assertEquals("reembolsado_parcial", json(res).get("estadoPago").asText());
        assertEquals("devuelto_parcial", json(res).get("estadoPedido").asText());
        assertEquals(0, new BigDecimal("7.50").compareTo(json(res).get("cobradoNeto").decimalValue()));
        assertEquals("reembolsado_parcial", estadoPagoPedido(r, ids[0]));
        assertEquals("devuelto_parcial", estadoPedido(r, ids[0]));
        assertEquals("reembolsado_parcial", escenario.texto(r.tenantId(), "select estado from pagos where id = ?", ids[1]));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from reembolsos where pago_id = ? and monto = 4.00", ids[1]));

        drenar();
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from comprobantes where tipo = '04'"),
                "la devolucion parcial no genera nota de credito (pendiente de criterio del SRI)");
    }

    @Test
    void reembolsoTotalTrasLaEntregaDejaDevueltoTotalYGeneraUnaNotaDeCredito() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID[] ids = pedidoEntregadoYPagado(r);
        drenar();   // factura
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from comprobantes where tipo = '01'"));

        Response res = reembolsar(r, "owner", ids[1], "11.50", "cliente se retira");
        assertEquals(201, res.statusCode(), res.asString());
        assertEquals("reembolsado", json(res).get("estadoPago").asText());
        assertEquals("devuelto_total", json(res).get("estadoPedido").asText());
        assertEquals("reembolsado", escenario.texto(r.tenantId(), "select estado from pagos where id = ?", ids[1]));

        drenar();

        assertEquals("1", notaCredito(r, ids[0], "count(*)"));
        assertEquals("generado", notaCredito(r, ids[0], "estado_sri"));
        assertEquals("000000001", notaCredito(r, ids[0], "secuencial"), "numeracion propia de las notas de credito");
        assertEquals("cliente se retira", notaCredito(r, ids[0], "motivo_modificacion"));
        assertEquals("11.50", notaCredito(r, ids[0], "importe_total::text"));
        assertEquals("true", escenario.texto(r.tenantId(), """
                select (n.comprobante_modificado_id = f.id)::text from comprobantes n
                  join comprobantes f on f.pedido_id = n.pedido_id and f.tipo = '01' where n.pedido_id = ? and n.tipo = '04'
                """, ids[0]), "acredita la factura del pedido");
        String clave = notaCredito(r, ids[0], "clave_acceso");
        assertEquals(49, clave.length());
        assertEquals("04", clave.substring(8, 10), "tipo de comprobante: nota de credito");
        UUID notaId = UUID.fromString(notaCredito(r, ids[0], "id::text"));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from comprobante_detalles where comprobante_id = ?", notaId));
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from jobs where tipo = 'sri.emitir' and payload->>'comprobanteId' = ? and payload->>'tipo' = '04'",
                notaId.toString()));
    }

    @Test
    void dosReembolsosParcialesQueSumanElTotalGeneranLaNotaUnaSolaVez() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID[] ids = pedidoEntregadoYPagado(r);
        drenar();

        assertEquals("reembolsado_parcial", json(reembolsar(r, "admin", ids[1], "5.00", "a")).get("estadoPago").asText());
        drenar();
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from comprobantes where tipo = '04'"));

        Response segundo = reembolsar(r, "admin", ids[1], "6.50", "b");
        assertEquals("reembolsado", json(segundo).get("estadoPago").asText());
        assertEquals("devuelto_total", json(segundo).get("estadoPedido").asText());
        drenar();

        assertEquals("1", notaCredito(r, ids[0], "count(*)"));
        assertEquals("b", notaCredito(r, ids[0], "motivo_modificacion"), "el motivo es el del reembolso que completo la devolucion");
    }

    @Test
    void siLaVentaSeDevuelveAntesDeFacturarLaNotaSeGeneraDespuesDeLaFactura() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID[] ids = pedidoEntregadoYPagado(r);
        // Aun no se drena PedidoPagado: no hay factura cuando se reembolsa.
        // (pedidoEntregadoYPagado drena antes de pagar, asi que PedidoPagado sigue pendiente.)
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from comprobantes"));
        assertEquals(201, reembolsar(r, "owner", ids[1], "11.50", "error de caja").statusCode());

        drenar();   // procesa PedidoPagado (factura) y PagoReembolsado en cualquier orden

        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from comprobantes where tipo = '01'"));
        assertEquals("1", notaCredito(r, ids[0], "count(*)"), "la nota se genera sin importar el orden de los eventos");
        assertEquals(2, escenario.contar(r.tenantId(), "select count(*) from jobs where tipo = 'sri.emitir'"),
                "un trabajo para la factura y otro para la nota");
    }

    @Test
    void reembolsarAntesDeEntregarYCancelarFunciona() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoConfirmado(r);
        UUID pagoId = pagar(r, pedidoId, "11.50");

        // Con dinero cobrado no se puede cancelar.
        Response rechazo = post(escenario.token(r, "admin"), "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "ya no lo quiere"));
        assertEquals(409, rechazo.statusCode(), rechazo.asString());

        Response res = reembolsar(r, "admin", pagoId, "11.50", "ya no lo quiere");
        assertEquals(201, res.statusCode(), res.asString());
        assertEquals("reembolsado", json(res).get("estadoPago").asText());
        assertEquals("confirmado", json(res).get("estadoPedido").asText(), "no se habia entregado: no pasa a devuelto");

        Response cancelado = post(escenario.token(r, "admin"), "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "ya no lo quiere"));
        assertEquals(200, cancelado.statusCode(), cancelado.asString());
        assertEquals("cancelado", estadoPedido(r, pedidoId));
    }

    @Test
    void conCobroNuevoSobreUnPedidoTotalmenteReembolsadoSeRechaza() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoConfirmado(r);
        UUID pagoId = pagar(r, pedidoId, "11.50");
        reembolsar(r, "admin", pagoId, "11.50", "x");

        Response otro = post(escenario.token(r, "cajero"), "/api/v1/pagos", Map.of("id", UuidV7.next().toString(),
                "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("11.50")));
        assertCodigo(409, "PEDIDO_REEMBOLSADO", otro);
    }

    @Test
    void trasUnReembolsoParcialSePuedeCobrarElFaltanteYElNetoManda() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoConfirmado(r);
        UUID pagoId = pagar(r, pedidoId, "11.50");
        reembolsar(r, "admin", pagoId, "4.00", "x");
        assertEquals("reembolsado_parcial", estadoPagoPedido(r, pedidoId));

        // Neto cobrado 7.50: el saldo es 4.00. Cobrar mas que eso se rechaza; cobrar exactamente eso lo deja pagado.
        Response demasiado = post(escenario.token(r, "cajero"), "/api/v1/pagos", Map.of("id", UuidV7.next().toString(),
                "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("4.01")));
        assertCodigo(409, "MONTO_EXCEDE_SALDO", demasiado);
        Response ok = post(escenario.token(r, "cajero"), "/api/v1/pagos", Map.of("id", UuidV7.next().toString(),
                "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("4.00")));
        assertEquals(201, ok.statusCode(), ok.asString());
        assertEquals("pagado", json(ok).get("estadoPago").asText());
    }

    @Test
    void validaMotivoMontoYTopeYElPagoQuedaIntacto() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoConfirmado(r);
        UUID pagoId = pagar(r, pedidoId, "11.50");

        assertEquals(400, reembolsar(r, "admin", pagoId, "1.00", " ").statusCode(), "motivo vacio");
        assertEquals(400, reembolsar(r, "admin", pagoId, "0", "x").statusCode(), "monto cero");
        assertEquals(400, reembolsar(r, "admin", pagoId, "-1", "x").statusCode(), "monto negativo");
        assertCodigo(409, "MONTO_EXCEDE_REEMBOLSABLE", reembolsar(r, "admin", pagoId, "11.51", "x"));
        assertCodigo(404, "PAGO_NO_ENCONTRADO", reembolsar(r, "admin", UuidV7.next(), "1.00", "x"));

        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from reembolsos"));
        assertEquals("pagado", estadoPagoPedido(r, pedidoId));
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from auditoria where accion = 'pago.reembolsar'"));
    }

    @Test
    void soloOwnerYAdminReembolsan() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoConfirmado(r);
        UUID pagoId = pagar(r, pedidoId, "11.50");

        assertEquals(403, reembolsar(r, "cajero", pagoId, "1.00", "x").statusCode());
        assertEquals(403, reembolsar(r, "mesero", pagoId, "1.00", "x").statusCode());
        assertEquals(403, reembolsar(r, "cocina", pagoId, "1.00", "x").statusCode());
        given().contentType(ContentType.JSON).body(Map.of("id", UuidV7.next().toString(), "monto", 1, "motivo", "x"))
                .when().post("/api/v1/pagos/" + pagoId + "/reembolsos").then().statusCode(401);
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from reembolsos"));
    }

    @Test
    void repetirElMismoReembolsoEsIdempotenteYReutilizarElIdConOtrosDatosEsConflicto() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoConfirmado(r);
        UUID pagoId = pagar(r, pedidoId, "11.50");
        UUID reembolsoId = UuidV7.next();

        assertEquals(201, reembolsar(r, "admin", reembolsoId, pagoId, "4.00", "x").statusCode());
        Response repetido = reembolsar(r, "admin", reembolsoId, pagoId, "4.00", "x");
        assertEquals(200, repetido.statusCode(), repetido.asString());
        assertCodigo(409, "REEMBOLSO_ID_REUTILIZADO", reembolsar(r, "admin", reembolsoId, pagoId, "5.00", "x"));

        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from reembolsos"));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from auditoria where accion = 'pago.reembolsar'"));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'PagoReembolsado'"));
    }

    @Test
    void elReembolsoQuedaAuditadoConQuienCuantoYPorQue() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoConfirmado(r);
        UUID pagoId = pagar(r, pedidoId, "11.50");

        reembolsar(r, "admin", pagoId, "4.00", "plato frio");

        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from auditoria where accion = 'pago.reembolsar' and entidad = 'pago' and entidad_id = ? and usuario_id = ?",
                pagoId, r.usuarioId()));
        assertEquals("plato frio", escenario.texto(r.tenantId(),
                "select despues->>'motivo' from auditoria where accion = 'pago.reembolsar' and entidad_id = ?", pagoId));
        assertEquals("4.00", escenario.texto(r.tenantId(),
                "select despues->>'monto' from auditoria where accion = 'pago.reembolsar' and entidad_id = ?", pagoId));
        assertEquals(r.usuarioId().toString(), escenario.texto(r.tenantId(),
                "select autorizado_por::text from reembolsos where pago_id = ?", pagoId));
    }

    @Test
    void unRestauranteNoPuedeReembolsarPagosDeOtro() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        UUID pagoDeA = pagar(a, pedidoConfirmado(a), "11.50");

        assertCodigo(404, "PAGO_NO_ENCONTRADO", reembolsar(b, "owner", pagoDeA, "1.00", "x"));
        assertEquals(0, escenario.contar(a.tenantId(), "select count(*) from reembolsos"));
    }

    @Test
    void anularUnItemPagadoExigeReembolsarPrimero() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoConfirmado(r);
        drenar();
        UUID item = UUID.fromString(escenario.texto(r.tenantId(), "select id::text from pedido_items where pedido_id = ?", pedidoId));
        UUID pagoId = pagar(r, pedidoId, "11.50");

        Response rechazo = post(escenario.token(r, "admin"), "/api/v1/cocina/items/" + item + "/anular", Map.of("motivo", "se acabo"));
        assertEquals(409, rechazo.statusCode(), rechazo.asString());

        reembolsar(r, "admin", pagoId, "11.50", "se acabo");
        Response ok = post(escenario.token(r, "admin"), "/api/v1/cocina/items/" + item + "/anular", Map.of("motivo", "se acabo"));
        assertEquals(200, ok.statusCode(), ok.asString());
    }
}
