package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.events.OutboxRelay;
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
 * Agregar items a un pedido ya tomado: totales, cocina por evento, idempotencia, estados que lo admiten, cobros y
 * factura. Contra la base real, con JWT firmados.
 */
@QuarkusTest
class AgregarItemsFlujoTest {

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

    private Response post(String token, String ruta) {
        return post(token, ruta, "{}");
    }

    private Response get(String token, String ruta) {
        return given().auth().oauth2(token).when().get(ruta);
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

    private static Map<String, Object> item(UUID id, UUID producto, int cantidad) {
        return Map.of("id", id.toString(), "productoId", producto.toString(), "cantidad", cantidad);
    }

    private Response agregar(Restaurante r, String rol, UUID pedidoId, Object... items) {
        return post(escenario.token(r, rol), "/api/v1/pedidos/" + pedidoId + "/items", Map.of("items", List.of(items)));
    }

    /** Pedido de salon con 1 unidad del producto base (10.00 + IVA 1.50 = 11.50). Sin confirmar. */
    private UUID pedido(Restaurante r) {
        UUID pedidoId = UuidV7.next();
        Response creado = post(escenario.token(r, "mesero"), "/api/v1/pedidos", Map.of("id", pedidoId.toString(), "canal", "salon",
                "mesaId", r.mesaId().toString(), "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", 1))));
        assertEquals(201, creado.statusCode(), creado.asString());
        return pedidoId;
    }

    private UUID pedidoConfirmadoYEnCocina(Restaurante r) {
        UUID pedidoId = pedido(r);
        assertEquals(200, post(escenario.token(r, "mesero"), "/api/v1/pedidos/" + pedidoId + "/confirmar").statusCode());
        drenar();
        return pedidoId;
    }

    private String texto(Restaurante r, String sql, Object... args) {
        return escenario.texto(r.tenantId(), sql, args);
    }

    private long contar(Restaurante r, String sql, Object... args) {
        return escenario.contar(r.tenantId(), sql, args);
    }

    private UUID itemDe(Restaurante r, UUID pedidoId, String nombre) {
        return UUID.fromString(texto(r, "select id::text from pedido_items where pedido_id = ? and nombre = ?", pedidoId, nombre));
    }

    private Response pagar(Restaurante r, UUID pedidoId, String monto) {
        return post(escenario.token(r, "cajero"), "/api/v1/pagos", Map.of("id", UuidV7.next().toString(),
                "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal(monto)));
    }

    // ------------------------------------------------------------ antes de cocina

    @Test
    void agregarEnUnPedidoSinConfirmarSumaAlTotalYLosItemsLlegaranAlConfirmar() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedido(r);

        Response res = agregar(r, "mesero", pedidoId, item(UuidV7.next(), bebida, 2));

        assertEquals(201, res.statusCode(), res.asString());
        assertEquals(0, new BigDecimal("20.00").compareTo(json(res).get("subtotal").decimalValue()), "10 + 2 x 5");
        assertEquals(0, new BigDecimal("23.00").compareTo(json(res).get("total").decimalValue()), "20 + 3.00 IVA");
        assertEquals(2, contar(r, "select count(*) from pedido_items where pedido_id = ?", pedidoId));
        assertEquals(0, contar(r, "select count(*) from outbox where tipo = 'ItemsAgregados'"), "cocina aun no tiene el pedido");

        post(escenario.token(r, "mesero"), "/api/v1/pedidos/" + pedidoId + "/confirmar");
        drenar();
        assertEquals(2, contar(r, "select count(*) from pedido_items where pedido_id = ? and estado_cocina = 'enviado'", pedidoId));
    }

    // -------------------------------------------------------------- con cocina

    @Test
    void losItemsNuevosLlegaranALaCocinaPorEvento() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmadoYEnCocina(r);

        Response res = agregar(r, "mesero", pedidoId, item(UuidV7.next(), bebida, 1));

        assertEquals(201, res.statusCode(), res.asString());
        assertEquals(0, new BigDecimal("17.25").compareTo(json(res).get("total").decimalValue()), "15 + 2.25 IVA");
        assertEquals("pendiente", texto(r, "select estado_cocina from pedido_items where pedido_id = ? and nombre = 'Bebida'", pedidoId),
                "depende del evento, no es instantaneo");
        assertEquals(1, contar(r, "select count(*) from outbox where tipo = 'ItemsAgregados' and agregado_id = ?", pedidoId));

        drenar();
        assertEquals("enviado", texto(r, "select estado_cocina from pedido_items where pedido_id = ? and nombre = 'Bebida'", pedidoId));
        Response pantalla = get(escenario.token(r, "cocina"), "/api/v1/cocina/items");
        assertEquals(2, json(pantalla).size(), "la pantalla de cocina ve el plato y la bebida");
        assertEquals("confirmado", texto(r, "select estado from pedidos where id = ?", pedidoId), "agregar no retrocede ni adelanta el estado");

        drenar();   // reentrega: no repite el envio
        assertEquals(1, contar(r, "select count(*) from eventos_procesados where consumidor = 'cocina.enviar-items-agregados'"));
    }

    @Test
    void conElPedidoEnPreparacionElNuevoItemRetrasaLaEntrega() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmadoYEnCocina(r);
        UUID plato = itemDe(r, pedidoId, "Encebollado");
        post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + plato + "/preparando");
        assertEquals("en_preparacion", texto(r, "select estado from pedidos where id = ?", pedidoId));

        assertEquals(201, agregar(r, "mesero", pedidoId, item(UuidV7.next(), bebida, 1)).statusCode());
        drenar();
        UUID nuevo = itemDe(r, pedidoId, "Bebida");

        post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + plato + "/listo");
        post(escenario.token(r, "mesero"), "/api/v1/cocina/items/" + plato + "/servido");
        assertEquals("en_preparacion", texto(r, "select estado from pedidos where id = ?", pedidoId),
                "el plato ya se sirvio pero falta la bebida nueva: el pedido no esta entregado");

        post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + nuevo + "/listo");
        post(escenario.token(r, "mesero"), "/api/v1/cocina/items/" + nuevo + "/servido");
        assertEquals("entregado", texto(r, "select estado from pedidos where id = ?", pedidoId));
    }

    @Test
    void unItemAgregadoSePuedeAnularYBajaElTotal() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmadoYEnCocina(r);
        agregar(r, "mesero", pedidoId, item(UuidV7.next(), bebida, 1));
        drenar();

        UUID nuevo = itemDe(r, pedidoId, "Bebida");
        assertEquals(200, post(escenario.token(r, "admin"), "/api/v1/cocina/items/" + nuevo + "/anular", Map.of("motivo", "se acabo")).statusCode());

        assertEquals("11.50", texto(r, "select total::text from pedidos where id = ?", pedidoId));
    }

    // ------------------------------------------------------------- idempotencia

    @Test
    void repetirLaMismaPeticionNoDuplicaItemsNiEventos() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmadoYEnCocina(r);
        UUID itemId = UuidV7.next();

        assertEquals(201, agregar(r, "mesero", pedidoId, item(itemId, bebida, 1)).statusCode());
        Response repetida = agregar(r, "mesero", pedidoId, item(itemId, bebida, 1));

        assertEquals(200, repetida.statusCode(), repetida.asString());
        assertEquals(2, contar(r, "select count(*) from pedido_items where pedido_id = ?", pedidoId));
        assertEquals(1, contar(r, "select count(*) from outbox where tipo = 'ItemsAgregados'"));
        assertEquals("17.25", texto(r, "select total::text from pedidos where id = ?", pedidoId));
    }

    @Test
    void mezclarIdsYaUsadosConNuevosOUsarElIdDeOtroPedidoEsConflicto() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmadoYEnCocina(r);
        UUID otroPedido = pedido(r);
        UUID itemId = UuidV7.next();
        agregar(r, "mesero", pedidoId, item(itemId, bebida, 1));
        UUID delOtro = itemDe(r, otroPedido, "Encebollado");

        assertCodigo(409, "ITEM_ID_REUTILIZADO", agregar(r, "mesero", pedidoId, item(itemId, bebida, 1), item(UuidV7.next(), bebida, 1)));
        assertCodigo(409, "ITEM_ID_REUTILIZADO", agregar(r, "mesero", pedidoId, item(delOtro, bebida, 1)));
        assertCodigo(422, "ITEM_ID_DUPLICADO", agregar(r, "mesero", pedidoId, item(itemId, bebida, 1), item(itemId, bebida, 2)));
        assertEquals(2, contar(r, "select count(*) from pedido_items where pedido_id = ?", pedidoId), "no se agrego nada de mas");
    }

    // -------------------------------------------------- estados que no lo admiten

    @Test
    void unPedidoListoEntregadoOCanceladoNoAdmiteItems() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");

        UUID listo = pedidoConfirmadoYEnCocina(r);
        post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + itemDe(r, listo, "Encebollado") + "/listo");
        assertEquals("listo", texto(r, "select estado from pedidos where id = ?", listo));
        assertCodigo(409, "PEDIDO_NO_ADMITE_ITEMS", agregar(r, "mesero", listo, item(UuidV7.next(), bebida, 1)));

        UUID entregado = pedidoConfirmadoYEnCocina(r);
        UUID plato = itemDe(r, entregado, "Encebollado");
        post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + plato + "/listo");
        post(escenario.token(r, "mesero"), "/api/v1/cocina/items/" + plato + "/servido");
        assertCodigo(409, "PEDIDO_NO_ADMITE_ITEMS", agregar(r, "mesero", entregado, item(UuidV7.next(), bebida, 1)));

        UUID cancelado = pedido(r);
        post(escenario.token(r, "admin"), "/api/v1/pedidos/" + cancelado + "/cancelar", Map.of("motivo", "x"));
        assertCodigo(409, "PEDIDO_NO_ADMITE_ITEMS", agregar(r, "mesero", cancelado, item(UuidV7.next(), bebida, 1)));

        assertEquals(0, contar(r, "select count(*) from pedido_items where nombre = 'Bebida'"));
    }

    @Test
    void unPedidoPagadoOReembolsadoNoAdmiteItems() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pagado = pedidoConfirmadoYEnCocina(r);
        assertEquals(201, pagar(r, pagado, "11.50").statusCode());
        assertCodigo(409, "PEDIDO_YA_PAGADO", agregar(r, "mesero", pagado, item(UuidV7.next(), bebida, 1)));

        UUID reembolsado = pedidoConfirmadoYEnCocina(r);
        UUID pagoId = UUID.fromString(json(pagar(r, reembolsado, "11.50")).get("pagoId").asText());
        post(escenario.token(r, "admin"), "/api/v1/pagos/" + pagoId + "/reembolsos",
                Map.of("id", UuidV7.next().toString(), "monto", new BigDecimal("4.00"), "motivo", "x"));
        assertCodigo(409, "PEDIDO_REEMBOLSADO", agregar(r, "mesero", reembolsado, item(UuidV7.next(), bebida, 1)));
    }

    // --------------------------------------------------------- cobro y factura

    @Test
    void conUnPagoParcialSePuedeAgregarYLaFacturaIncluyeTodo() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID pedidoId = pedidoConfirmadoYEnCocina(r);
        assertEquals("parcial", json(pagar(r, pedidoId, "5.00")).get("estadoPago").asText());

        assertEquals(201, agregar(r, "mesero", pedidoId, item(UuidV7.next(), bebida, 1)).statusCode());
        assertEquals("parcial", texto(r, "select estado_pago from pedidos where id = ?", pedidoId));

        // Saldo = 17.25 - 5.00 = 12.25: un cobro mayor se rechaza, exactamente el saldo deja el pedido pagado.
        assertCodigo(409, "MONTO_EXCEDE_SALDO", pagar(r, pedidoId, "12.26"));
        Response ultimo = pagar(r, pedidoId, "12.25");
        assertEquals("pagado", json(ultimo).get("estadoPago").asText());

        drenar();
        assertEquals("15.00", texto(r, "select subtotal_sin_impuestos::text from comprobantes where pedido_id = ? and tipo = '01'", pedidoId));
        assertEquals("17.25", texto(r, "select importe_total::text from comprobantes where pedido_id = ? and tipo = '01'", pedidoId));
        assertEquals(2, contar(r, "select count(*) from comprobante_detalles d join comprobantes c on c.id = d.comprobante_id "
                + "where c.pedido_id = ? and c.tipo = '01'", pedidoId), "la factura lleva el plato y la bebida");
    }

    // ------------------------------------------------------------- validaciones

    @Test
    void validaProductosCantidadesYRoles() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID agotado = escenario.crearProducto(r.tenantId(), "Agotado", "5.00");
        escenario.ejecutar(r.tenantId(), "update productos set activo = false where id = ?", agotado);
        UUID pedidoId = pedido(r);

        assertCodigo(422, "PRODUCTO_NO_ENCONTRADO", agregar(r, "mesero", pedidoId, item(UuidV7.next(), UuidV7.next(), 1)));
        assertCodigo(409, "PRODUCTO_NO_DISPONIBLE", agregar(r, "mesero", pedidoId, item(UuidV7.next(), agotado, 1)));
        assertEquals(400, agregar(r, "mesero", pedidoId, item(UuidV7.next(), bebida, 0)).statusCode(), "cantidad cero");
        assertEquals(400, post(escenario.token(r, "mesero"), "/api/v1/pedidos/" + pedidoId + "/items", Map.of("items", List.of())).statusCode());
        assertCodigo(404, "PEDIDO_NO_ENCONTRADO", agregar(r, "mesero", UuidV7.next(), item(UuidV7.next(), bebida, 1)));
        assertEquals(403, agregar(r, "cocina", pedidoId, item(UuidV7.next(), bebida, 1)).statusCode());
        assertEquals(403, agregar(r, "repartidor", pedidoId, item(UuidV7.next(), bebida, 1)).statusCode());
        given().contentType(ContentType.JSON).body(Map.of("items", List.of(item(UuidV7.next(), bebida, 1))))
                .when().post("/api/v1/pedidos/" + pedidoId + "/items").then().statusCode(401);

        assertEquals(1, contar(r, "select count(*) from pedido_items where pedido_id = ?", pedidoId), "no se agrego nada");
        assertEquals("11.50", texto(r, "select total::text from pedidos where id = ?", pedidoId));
    }

    @Test
    void otroRestauranteNoPuedeAgregarItemsAUnPedidoAjeno() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante intruso = escenario.crearRestaurante("10.00");
        UUID pedidoDeA = pedido(a);

        assertCodigo(404, "PEDIDO_NO_ENCONTRADO", agregar(intruso, "owner", pedidoDeA, item(UuidV7.next(), intruso.productoId(), 1)));
        assertEquals(1, contar(a, "select count(*) from pedido_items where pedido_id = ?", pedidoDeA));
    }

    // ---------------------------------------------------------------- delivery

    @Test
    void enUnDeliveryElEnvioSeConservaYDespuesDeAsignarNoSeAdmiten() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID bebida = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.50", "0");
        String caja = escenario.token(r, "cajero");
        UUID cliente = UuidV7.next();
        post(caja, "/api/v1/delivery/clientes", Map.of("id", cliente.toString(), "nombre", "Ana", "tipoIdentificacion", "05",
                "identificacion", Identificaciones.cedulaAleatoria(), "telefono", "099"));
        UUID direccion = UuidV7.next();
        post(caja, "/api/v1/delivery/clientes/" + cliente + "/direcciones", Map.of("id", direccion.toString(), "direccion", "Calle 1"));
        UUID pedidoId = UuidV7.next();
        post(caja, "/api/v1/delivery/pedidos", Map.of("id", pedidoId.toString(), "clienteId", cliente.toString(),
                "direccionId", direccion.toString(), "zonaId", zona.toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", 1))));

        Response res = agregar(r, "cajero", pedidoId, item(UuidV7.next(), bebida, 1));

        assertEquals(201, res.statusCode(), res.asString());
        assertEquals(0, new BigDecimal("19.75").compareTo(json(res).get("total").decimalValue()), "15 + 2.25 IVA + 2.50 de envio");
        assertEquals("2.50", texto(r, "select costo_envio::text from pedidos where id = ?", pedidoId));

        // Ya listo y con repartidor asignado, no se admite mas.
        post(caja, "/api/v1/pedidos/" + pedidoId + "/confirmar");
        drenar();
        for (UUID it : List.of(itemDe(r, pedidoId, "Encebollado"), itemDe(r, pedidoId, "Bebida"))) {
            post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + it + "/listo");
        }
        drenar();
        UUID repartidor = escenario.crearRepartidor(r);
        post(caja, "/api/v1/delivery/entregas/" + pedidoId + "/asignar", Map.of("repartidorId", repartidor.toString()));
        assertCodigo(409, "PEDIDO_NO_ADMITE_ITEMS", agregar(r, "cajero", pedidoId, item(UuidV7.next(), bebida, 1)));
    }
}
