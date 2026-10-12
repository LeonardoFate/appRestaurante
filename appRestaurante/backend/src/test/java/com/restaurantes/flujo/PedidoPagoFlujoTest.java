package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Flujo completo por HTTP, con JWT firmados y contra la base real con RLS: crear pedido -> cobrar -> evento
 * en el outbox. No hay mocks de la base de datos ni de la identidad.
 */
@QuarkusTest
class PedidoPagoFlujoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ROLES_CAJA = List.of("cajero");

    @Inject
    Escenario escenario;

    // ---------------------------------------------------------------- helpers

    private Response crearPedido(String token, Map<String, Object> cuerpo) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(cuerpo).when().post("/api/v1/pedidos");
    }

    private Response pagar(String token, Map<String, Object> cuerpo) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(cuerpo).when().post("/api/v1/pagos");
    }

    private Response obtener(String token, UUID pedidoId) {
        return given().auth().oauth2(token).when().get("/api/v1/pedidos/" + pedidoId);
    }

    /** Pedido de salon con {@code cantidad} unidades del producto del restaurante. */
    private static Map<String, Object> pedidoSalon(Restaurante r, UUID id, String cantidad) {
        return Map.of(
                "id", id.toString(),
                "canal", "salon",
                "mesaId", r.mesaId().toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal(cantidad), "nota", "sin cebolla")));
    }

    private static Map<String, Object> pago(UUID id, UUID pedidoId, String metodo, String monto) {
        return Map.of("id", id.toString(), "pedidoId", pedidoId.toString(), "metodo", metodo, "monto", new BigDecimal(monto));
    }

    private static JsonNode json(Response r) {
        try {
            return JSON.readTree(r.asString());
        } catch (IOException e) {
            throw new IllegalStateException("Respuesta no es JSON: " + r.asString(), e);
        }
    }

    private static void assertDinero(String esperado, JsonNode nodo) {
        assertEquals(0, new BigDecimal(esperado).compareTo(nodo.decimalValue()), "esperado " + esperado + " pero fue " + nodo);
    }

    // ------------------------------------------------------------ crear pedido

    @Test
    void creaUnPedidoCalculandoLosTotalesDesdeElMenu() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "mesero");
        UUID pedidoId = UuidV7.next();

        Response respuesta = crearPedido(token, pedidoSalon(r, pedidoId, "2"));

        assertEquals(201, respuesta.statusCode(), respuesta.asString());
        JsonNode p = json(respuesta);
        assertEquals(pedidoId.toString(), p.get("id").asText());
        assertEquals(1, p.get("numero").asInt());
        assertEquals("creado", p.get("estado").asText());
        assertEquals("sin_pago", p.get("estadoPago").asText());
        assertDinero("20.00", p.get("subtotal"));
        assertDinero("3.00", p.get("iva"));
        assertDinero("23.00", p.get("total"));
        assertEquals(1, p.get("items").size());
        assertEquals("Encebollado", p.get("items").get(0).get("nombre").asText());
        assertDinero("10.00", p.get("items").get(0).get("precioUnitario"));

        // Quedo todo en la base: pedido, item e historial.
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos where id = ?", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedido_items where pedido_id = ?", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from pedido_historial where pedido_id = ? and estado_nuevo = 'creado'", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos where id = ? and usuario_id = ?", pedidoId, r.usuarioId()));

        Response lectura = obtener(token, pedidoId);
        assertEquals(200, lectura.statusCode());
        assertDinero("23.00", json(lectura).get("total"));
    }

    @Test
    void repetirLaMismaOrdenEsIdempotenteYNoConsumeNumero() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "mesero");
        UUID pedidoId = UuidV7.next();

        Response primera = crearPedido(token, pedidoSalon(r, pedidoId, "1"));
        Response segunda = crearPedido(token, pedidoSalon(r, pedidoId, "1"));

        assertEquals(201, primera.statusCode());
        assertEquals(200, segunda.statusCode(), "la repeticion no crea nada nuevo");
        assertEquals(json(primera).get("numero").asInt(), json(segunda).get("numero").asInt());
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos"));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedido_historial where pedido_id = ?", pedidoId));

        // Sin huecos: el siguiente pedido nuevo es el numero 2.
        Response siguiente = crearPedido(token, pedidoSalon(r, UuidV7.next(), "1"));
        assertEquals(2, json(siguiente).get("numero").asInt());
    }

    @Test
    void ignoraLosPreciosYTotalesQueEnvieElCliente() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "mesero");

        Map<String, Object> item = Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("1"),
                "precioUnitario", new BigDecimal("0.01"));
        Map<String, Object> cuerpo = Map.of("canal", "salon", "mesaId", r.mesaId().toString(), "items", List.of(item),
                "total", new BigDecimal("0.01"));

        Response respuesta = crearPedido(token, cuerpo);
        assertEquals(201, respuesta.statusCode(), respuesta.asString());
        assertDinero("11.50", json(respuesta).get("total"));
    }

    @Test
    void rechazaPedidosInvalidos() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "mesero");
        Map<String, Object> item = Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("1"));

        // Sin items: la validacion del formato responde 400.
        assertEquals(400, crearPedido(token, Map.of("canal", "salon", "mesaId", r.mesaId().toString(), "items", List.of())).statusCode());
        // Cantidad cero: 400.
        assertEquals(400, crearPedido(token, Map.of("canal", "salon", "mesaId", r.mesaId().toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("0"))))).statusCode());

        // Reglas de negocio: 422 con un codigo estable.
        Response sinMesa = crearPedido(token, Map.of("canal", "salon", "items", List.of(item)));
        assertEquals(422, sinMesa.statusCode());
        assertEquals("MESA_REQUERIDA", json(sinMesa).get("codigo").asText());

        Response delivery = crearPedido(token, Map.of("canal", "delivery_propio", "items", List.of(item)));
        assertEquals(422, delivery.statusCode());
        assertEquals("CANAL_NO_SOPORTADO", json(delivery).get("codigo").asText());

        Response canalRaro = crearPedido(token, Map.of("canal", "drone", "items", List.of(item)));
        assertEquals("CANAL_INVALIDO", json(canalRaro).get("codigo").asText());

        Response productoFantasma = crearPedido(token, Map.of("canal", "para_llevar",
                "items", List.of(Map.of("productoId", UuidV7.next().toString(), "cantidad", new BigDecimal("1")))));
        assertEquals(422, productoFantasma.statusCode());
        assertEquals("PRODUCTO_NO_ENCONTRADO", json(productoFantasma).get("codigo").asText());

        // Nada de lo anterior dejo pedidos a medias.
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from pedidos"));
    }

    // ------------------------------------------------------ seguridad y tenants

    @Test
    void sinTokenEs401YSinRolEs403() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = UuidV7.next();

        assertEquals(401, given().contentType(ContentType.JSON).body(pedidoSalon(r, pedidoId, "1")).when().post("/api/v1/pedidos").statusCode());
        assertEquals(401, given().when().get("/api/v1/pedidos/" + pedidoId).statusCode());
        assertEquals(403, crearPedido(escenario.token(r, "cocina"), pedidoSalon(r, pedidoId, "1")).statusCode(),
                "un usuario de cocina no toma pedidos ni cobra");
        assertEquals(403, pagar(escenario.token(r, "repartidor"), pago(UuidV7.next(), pedidoId, "efectivo", "1.00")).statusCode());
    }

    @Test
    void unTokenSinTenantNoSirve() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String sinTenant = io.smallrye.jwt.build.Jwt.issuer("restaurantes").subject(r.usuarioId().toString())
                .groups(java.util.Set.of("mesero")).sign();

        assertEquals(403, crearPedido(sinTenant, pedidoSalon(r, UuidV7.next(), "1")).statusCode());
    }

    @Test
    void otroRestauranteNoVeNiCobraNiUsaLosProductosDeOtro() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        String tokenA = escenario.token(a, "mesero");
        String tokenB = escenario.token(b, "cajero");
        UUID pedidoA = UuidV7.next();
        assertEquals(201, crearPedido(tokenA, pedidoSalon(a, pedidoA, "1")).statusCode());

        // B no ve el pedido de A.
        assertEquals(404, obtener(tokenB, pedidoA).statusCode());
        // B no puede cobrar el pedido de A.
        Response cobro = pagar(tokenB, pago(UuidV7.next(), pedidoA, "efectivo", "1.00"));
        assertEquals(404, cobro.statusCode());
        assertEquals(0, escenario.contar(a.tenantId(), "select count(*) from pagos"));
        // B no puede vender el producto de A.
        Response ajeno = crearPedido(tokenB, Map.of("canal", "salon", "mesaId", b.mesaId().toString(),
                "items", List.of(Map.of("productoId", a.productoId().toString(), "cantidad", new BigDecimal("1")))));
        assertEquals(422, ajeno.statusCode());
        assertEquals("PRODUCTO_NO_ENCONTRADO", json(ajeno).get("codigo").asText());
        // B no puede ubicar su pedido en una mesa de A (la clave foranea compuesta lo impide).
        Response mesaAjena = crearPedido(tokenB, pedidoSalon(a, UuidV7.next(), "1"));
        assertEquals(422, mesaAjena.statusCode(), mesaAjena.asString());
        assertEquals(0, escenario.contar(b.tenantId(), "select count(*) from pedidos"));
    }

    // ----------------------------------------------------------------- cobros

    @Test
    void cobraEnDosPartesYEmiteElEventoSoloAlCompletar() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String mesero = escenario.token(r, "mesero");
        String cajero = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, crearPedido(mesero, pedidoSalon(r, pedidoId, "2")).statusCode());   // total 23.00

        Response parcial = pagar(cajero, pago(UuidV7.next(), pedidoId, "efectivo", "10.00"));
        assertEquals(201, parcial.statusCode(), parcial.asString());
        assertEquals("parcial", json(parcial).get("estadoPago").asText());
        assertDinero("13.00", json(parcial).get("saldo"));
        assertEquals("parcial", json(obtener(mesero, pedidoId)).get("estadoPago").asText());
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'PedidoPagado' and agregado_id = ?", pedidoId),
                "mientras haya saldo no hay evento");

        Response total = pagar(cajero, pago(UuidV7.next(), pedidoId, "tarjeta_datafono", "13.00"));
        assertEquals(201, total.statusCode(), total.asString());
        assertEquals("pagado", json(total).get("estadoPago").asText());
        assertDinero("0.00", json(total).get("saldo"));
        assertEquals("pagado", json(obtener(mesero, pedidoId)).get("estadoPago").asText());
        assertEquals(0, new BigDecimal("23.00").compareTo(
                escenario.sumar(r.tenantId(), "select sum(monto) from pagos where pedido_id = ?", pedidoId)));

        // Un unico evento PedidoPagado, escrito en la misma transaccion del cobro.
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = 'PedidoPagado' and agregado_id = ?", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from outbox where tipo = 'PedidoPagado' and agregado_id = ? and payload->>'total' = '23.00'", pedidoId));
    }

    @Test
    void noPermiteCobrarMasDelSaldoNiDosVecesElMismoPedido() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, crearPedido(token, pedidoSalon(r, pedidoId, "2")).statusCode());   // 23.00

        Response demasiado = pagar(token, pago(UuidV7.next(), pedidoId, "efectivo", "23.01"));
        assertEquals(409, demasiado.statusCode());
        assertEquals("MONTO_EXCEDE_SALDO", json(demasiado).get("codigo").asText());
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from pagos"));

        assertEquals(201, pagar(token, pago(UuidV7.next(), pedidoId, "efectivo", "23.00")).statusCode());
        Response otraVez = pagar(token, pago(UuidV7.next(), pedidoId, "efectivo", "1.00"));
        assertEquals(409, otraVez.statusCode());
        assertEquals("PEDIDO_YA_PAGADO", json(otraVez).get("codigo").asText());
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pagos"));
    }

    @Test
    void repetirElMismoPagoEsIdempotente() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, crearPedido(token, pedidoSalon(r, pedidoId, "2")).statusCode());
        UUID pagoId = UuidV7.next();

        Response primero = pagar(token, pago(pagoId, pedidoId, "efectivo", "10.00"));
        Response repetido = pagar(token, pago(pagoId, pedidoId, "efectivo", "10.00"));

        assertEquals(201, primero.statusCode());
        assertEquals(200, repetido.statusCode(), "un reintento del POS no cobra dos veces");
        assertDinero("13.00", json(repetido).get("saldo"));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pagos where pedido_id = ?", pedidoId));

        // Reusar el id con otro monto es un error, no un segundo cobro.
        Response distinto = pagar(token, pago(pagoId, pedidoId, "efectivo", "5.00"));
        assertEquals(409, distinto.statusCode());
        assertEquals("PAGO_ID_REUTILIZADO", json(distinto).get("codigo").asText());
    }

    @Test
    void laPasarelaTodaviaNoEstaDisponible() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, crearPedido(token, pedidoSalon(r, pedidoId, "1")).statusCode());

        Response respuesta = pagar(token, pago(UuidV7.next(), pedidoId, "pasarela", "1.00"));
        assertEquals(422, respuesta.statusCode());
        assertEquals("METODO_NO_SOPORTADO", json(respuesta).get("codigo").asText());
    }

    @Test
    void siFallaElCambioDeEstadoNoQuedaElPagoNiElEvento() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, crearPedido(token, pedidoSalon(r, pedidoId, "1")).statusCode());      // 11.50
        // El pedido se cancela: el modulo pedidos rechazara el cambio de estado de cobro DESPUES de que pagos inserte.
        escenario.ejecutar(r.tenantId(), "update pedidos set estado = 'cancelado' where id = ?", pedidoId);

        Response respuesta = pagar(token, pago(UuidV7.next(), pedidoId, "efectivo", "11.50"));

        assertEquals(409, respuesta.statusCode());
        assertEquals("PEDIDO_NO_COBRABLE", json(respuesta).get("codigo").asText());
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from pagos"), "el pago se revierte con la transaccion");
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from outbox where agregado_id = ?", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos where id = ? and estado_pago = 'sin_pago'", pedidoId));
    }
}
