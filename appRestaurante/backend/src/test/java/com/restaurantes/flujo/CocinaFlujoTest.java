package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.junit.jupiter.api.Test;

/**
 * Maquina de estados del pedido y cocina, de extremo a extremo: confirmar -> evento -> relay -> items en la estacion ->
 * cocina marca items -> el pedido avanza solo -> se cobra y se cierra. Contra la base real, con JWT firmados.
 */
@QuarkusTest
class CocinaFlujoTest {

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

    private Response post(String token, String ruta) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body("{}").when().post(ruta);
    }

    private Response post(String token, String ruta, Object cuerpo) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(cuerpo).when().post(ruta);
    }

    private Response get(String token, String ruta) {
        return given().auth().oauth2(token).when().get(ruta);
    }

    /** Crea un pedido de salon con las lineas indicadas (producto, cantidad) y devuelve su id. */
    private UUID pedido(Restaurante r, String token, Object... productoYCantidad) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < productoYCantidad.length; i += 2) {
            items.add(Map.of("productoId", productoYCantidad[i].toString(), "cantidad", new BigDecimal(productoYCantidad[i + 1].toString())));
        }
        UUID pedidoId = UuidV7.next();
        Response respuesta = post(token, "/api/v1/pedidos", Map.of("id", pedidoId.toString(), "canal", "salon",
                "mesaId", r.mesaId().toString(), "items", items));
        assertEquals(201, respuesta.statusCode(), respuesta.asString());
        return pedidoId;
    }

    private void confirmarYEnviar(String token, UUID pedidoId) {
        Response confirmada = post(token, "/api/v1/pedidos/" + pedidoId + "/confirmar");
        assertEquals(200, confirmada.statusCode(), confirmada.asString());
        drenar();
    }

    private void drenar() {
        for (int i = 0; i < 50 && relay.publicarLote() > 0; i++) {
            // sigue
        }
    }

    private UUID item(Restaurante r, UUID pedidoId, String nombre) {
        return UUID.fromString(escenario.texto(r.tenantId(), "select id::text from pedido_items where pedido_id = ? and nombre = ?", pedidoId, nombre));
    }

    private String estadoPedido(Restaurante r, UUID pedidoId) {
        return escenario.texto(r.tenantId(), "select estado from pedidos where id = ?", pedidoId);
    }

    private String estadoItem(Restaurante r, UUID itemId) {
        return escenario.texto(r.tenantId(), "select estado_cocina from pedido_items where id = ?", itemId);
    }

    private long eventos(Restaurante r, String tipo, UUID pedidoId) {
        return escenario.contar(r.tenantId(), "select count(*) from outbox where tipo = ? and agregado_id = ?", tipo, pedidoId);
    }

    /** Historial del pedido como "anterior>nuevo,..." en orden cronologico ('-' si no hay estado anterior). */
    private String historial(Restaurante r, UUID pedidoId) {
        return escenario.texto(r.tenantId(), """
                select string_agg(coalesce(estado_anterior, '-') || '>' || estado_nuevo, ',' order by ocurrido_en)
                  from pedido_historial where pedido_id = ?
                """, pedidoId);
    }

    private void cobrarTodo(String token, UUID pedidoId, String monto) {
        Response pago = post(token, "/api/v1/pagos", Map.of("id", UuidV7.next().toString(), "pedidoId", pedidoId.toString(),
                "metodo", "efectivo", "monto", new BigDecimal(monto)));
        assertEquals(201, pago.statusCode(), pago.asString());
    }

    private static void assertCodigo(int estado, String codigo, Response r) {
        assertEquals(estado, r.statusCode(), r.asString());
        assertEquals(codigo, json(r).get("codigo").asText());
    }

    /** Restaurante con una estacion "Parrilla" asignada a su producto. */
    private Restaurante conParrilla() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID parrilla = escenario.crearEstacion(r.tenantId(), r.sucursalId(), "Parrilla");
        escenario.asignarEstacion(r.tenantId(), r.productoId(), r.sucursalId(), parrilla);
        return r;
    }

    // ----------------------------------------------------- confirmar y enviar

    @Test
    void confirmarMandaLosItemsACocinaPorEventoYNoAntes() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "2");
        UUID item = item(r, pedidoId, "Encebollado");

        Response confirmada = post(mesero, "/api/v1/pedidos/" + pedidoId + "/confirmar");

        assertEquals(200, confirmada.statusCode());
        assertEquals("confirmado", json(confirmada).get("estado").asText());
        assertEquals("pendiente", estadoItem(r, item), "el item aun no llego a cocina: depende del evento");
        assertEquals(1, eventos(r, "PedidoConfirmado", pedidoId));
        assertEquals(0, get(cocina, "/api/v1/cocina/items").jsonPath().getList("$").size(), "la pantalla de cocina aun no lo ve");

        drenar();

        assertEquals("enviado", estadoItem(r, item));
        assertNotNull(escenario.texto(r.tenantId(), "select enviado_en::text from pedido_items where id = ?", item));
        List<Map<String, Object>> pantalla = get(cocina, "/api/v1/cocina/items").jsonPath().getList("$");
        assertEquals(1, pantalla.size());
        assertEquals(item.toString(), pantalla.get(0).get("itemId"));
        assertEquals("Encebollado", pantalla.get(0).get("nombre"));
        assertEquals("enviado", pantalla.get(0).get("estado"));
        assertEquals("M1", pantalla.get(0).get("mesa"), "la mesa se muestra por su codigo visible");
        assertEquals(1, ((Number) pantalla.get(0).get("numeroPedido")).intValue());
        assertEquals("confirmado", estadoPedido(r, pedidoId), "recien enviado: el pedido sigue confirmado");
    }

    @Test
    void confirmarDosVecesEsIdempotenteYNoRepiteElEvento() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1");

        assertEquals(200, post(mesero, "/api/v1/pedidos/" + pedidoId + "/confirmar").statusCode());
        assertEquals(200, post(mesero, "/api/v1/pedidos/" + pedidoId + "/confirmar").statusCode());

        assertEquals(1, eventos(r, "PedidoConfirmado", pedidoId));
        assertEquals("->creado,creado>confirmado", historial(r, pedidoId));
    }

    @Test
    void elProductoSinEstacionVaALaEstacionPorDefectoDeLaSucursal() {
        Restaurante r = escenario.crearRestaurante("10.00");   // el producto NO tiene estacion
        UUID barra = escenario.crearEstacion(r.tenantId(), r.sucursalId(), "Barra");
        escenario.crearEstacion(r.tenantId(), r.sucursalId(), "Parrilla");
        String mesero = escenario.token(r, "mesero");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1");

        confirmarYEnviar(mesero, pedidoId);

        assertEquals(barra.toString(), escenario.texto(r.tenantId(),
                "select estacion_id::text from pedido_items where pedido_id = ?", pedidoId), "la primera por nombre");
    }

    @Test
    void sinEstacionesLosItemsIgualLlegaAlaCocina() {
        Restaurante r = escenario.crearRestaurante("10.00");   // sin estaciones
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1");

        confirmarYEnviar(mesero, pedidoId);

        assertNull(escenario.texto(r.tenantId(), "select estacion_id::text from pedido_items where pedido_id = ?", pedidoId));
        assertEquals(1, get(cocina, "/api/v1/cocina/items").jsonPath().getList("$").size(), "se ve en la cola general");
    }

    // ----------------------------------------------------------- ciclo de vida

    @Test
    void elPedidoAvanzaSoloConLosItemsYSeCierraAlCobrarse() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        String cajero = escenario.token(r, "cajero");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "2");   // total 23.00
        UUID item = item(r, pedidoId, "Encebollado");
        confirmarYEnviar(mesero, pedidoId);

        assertEquals(200, post(cocina, "/api/v1/cocina/items/" + item + "/preparando").statusCode());
        assertEquals("en_preparacion", estadoPedido(r, pedidoId));

        assertEquals(200, post(cocina, "/api/v1/cocina/items/" + item + "/listo").statusCode());
        assertEquals("listo", estadoPedido(r, pedidoId));
        assertEquals(1, eventos(r, "PedidoListo", pedidoId), "avisa que el pedido esta listo");

        assertEquals(200, post(mesero, "/api/v1/cocina/items/" + item + "/servido").statusCode());
        assertEquals("entregado", estadoPedido(r, pedidoId));
        assertEquals("servido", estadoItem(r, item));

        // En el salon se paga al final: al cobrarse un pedido entregado, se cierra solo.
        cobrarTodo(cajero, pedidoId, "23.00");
        assertEquals("cerrado", estadoPedido(r, pedidoId));
        assertEquals("->creado,creado>confirmado,confirmado>en_preparacion,en_preparacion>listo,listo>entregado,entregado>cerrado",
                historial(r, pedidoId));
        assertEquals("sistema", escenario.texto(r.tenantId(),
                "select origen from pedido_historial where pedido_id = ? and estado_nuevo = 'cerrado'", pedidoId),
                "el cierre automatico lo hace el sistema, no una persona");
        assertEquals(cocinaUsuario(r), escenario.texto(r.tenantId(),
                "select usuario_id::text from pedido_historial where pedido_id = ? and estado_nuevo = 'en_preparacion'", pedidoId));
    }

    private static String cocinaUsuario(Restaurante r) {
        return r.usuarioId().toString();
    }

    @Test
    void siYaEstabaPagadoSeCierraAlEntregar() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        String cajero = escenario.token(r, "cajero");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1");   // 11.50
        UUID item = item(r, pedidoId, "Encebollado");
        confirmarYEnviar(mesero, pedidoId);
        cobrarTodo(cajero, pedidoId, "11.50");
        assertEquals("confirmado", estadoPedido(r, pedidoId), "pagar no cambia el estado de preparacion");

        post(cocina, "/api/v1/cocina/items/" + item + "/listo");
        post(mesero, "/api/v1/cocina/items/" + item + "/servido");

        assertEquals("cerrado", estadoPedido(r, pedidoId));
        assertEquals("->creado,creado>confirmado,confirmado>en_preparacion,en_preparacion>listo,listo>entregado,entregado>cerrado",
                historial(r, pedidoId), "marcar 'listo' desde enviado recorre los pasos intermedios");
    }

    @Test
    void conVariosItemsElPedidoSigueAlMasAtrasadoYAvisaUnaSolaVez() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID parrilla = escenario.crearEstacion(r.tenantId(), r.sucursalId(), "Parrilla");
        UUID bar = escenario.crearEstacion(r.tenantId(), r.sucursalId(), "Bar");
        UUID cerveza = escenario.crearProducto(r.tenantId(), "Cerveza", "3.00");
        escenario.asignarEstacion(r.tenantId(), r.productoId(), r.sucursalId(), parrilla);
        escenario.asignarEstacion(r.tenantId(), cerveza, r.sucursalId(), bar);
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1", cerveza, "2");
        UUID plato = item(r, pedidoId, "Encebollado");
        UUID bebida = item(r, pedidoId, "Cerveza");
        confirmarYEnviar(mesero, pedidoId);

        // Cada estacion ve solo lo suyo.
        assertEquals(List.of("Encebollado"), get(cocina, "/api/v1/cocina/items?estacion=" + parrilla).jsonPath().getList("nombre"));
        assertEquals(List.of("Cerveza"), get(cocina, "/api/v1/cocina/items?estacion=" + bar).jsonPath().getList("nombre"));
        assertEquals(2, get(cocina, "/api/v1/cocina/items").jsonPath().getList("$").size());
        assertEquals(2, get(cocina, "/api/v1/cocina/estaciones").jsonPath().getList("$").size());

        post(cocina, "/api/v1/cocina/items/" + plato + "/listo");
        assertEquals("en_preparacion", estadoPedido(r, pedidoId), "el plato esta listo pero la bebida aun no");
        post(cocina, "/api/v1/cocina/items/" + bebida + "/preparando");
        assertEquals("en_preparacion", estadoPedido(r, pedidoId));
        post(cocina, "/api/v1/cocina/items/" + bebida + "/listo");
        assertEquals("listo", estadoPedido(r, pedidoId), "todo listo");
        assertEquals(1, eventos(r, "PedidoListo", pedidoId));

        post(mesero, "/api/v1/cocina/items/" + plato + "/servido");
        assertEquals("listo", estadoPedido(r, pedidoId), "falta servir la bebida: el pedido no esta entregado");
        post(mesero, "/api/v1/cocina/items/" + bebida + "/servido");
        assertEquals("entregado", estadoPedido(r, pedidoId));
    }

    @Test
    void laPantallaOrdenaLosPedidosMasAntiguosPrimero() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        UUID primero = pedido(r, mesero, r.productoId(), "1");
        confirmarYEnviar(mesero, primero);
        UUID segundo = pedido(r, mesero, r.productoId(), "1");
        confirmarYEnviar(mesero, segundo);

        List<String> orden = get(cocina, "/api/v1/cocina/items").jsonPath().getList("pedidoId");

        assertEquals(List.of(primero.toString(), segundo.toString()), orden);
    }

    @Test
    void unItemListoOServidoDejaDeAparecerEnLaPantallaSegunCorresponda() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1");
        UUID item = item(r, pedidoId, "Encebollado");
        confirmarYEnviar(mesero, pedidoId);

        post(cocina, "/api/v1/cocina/items/" + item + "/listo");
        assertEquals(1, get(cocina, "/api/v1/cocina/items").jsonPath().getList("$").size(), "listo sigue visible hasta servirse");
        post(mesero, "/api/v1/cocina/items/" + item + "/servido");

        assertEquals(0, get(cocina, "/api/v1/cocina/items").jsonPath().getList("$").size(), "servido ya no esta en la cola");
    }

    // ------------------------------------------------------ transiciones invalidas

    @Test
    void rechazaLasTransicionesInvalidasYRepetirUnaValidaEsIdempotente() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1");
        UUID item = item(r, pedidoId, "Encebollado");

        // Aun 'pendiente': cocina no puede empezarlo.
        assertCodigo(409, "TRANSICION_INVALIDA", post(cocina, "/api/v1/cocina/items/" + item + "/preparando"));
        confirmarYEnviar(mesero, pedidoId);
        // 'enviado': no se puede servir sin estar listo.
        assertCodigo(409, "TRANSICION_INVALIDA", post(mesero, "/api/v1/cocina/items/" + item + "/servido"));

        assertEquals(200, post(cocina, "/api/v1/cocina/items/" + item + "/preparando").statusCode());
        String preparandoEn = escenario.texto(r.tenantId(), "select preparando_en::text from pedido_items where id = ?", item);
        Response repetido = post(cocina, "/api/v1/cocina/items/" + item + "/preparando");

        assertEquals(200, repetido.statusCode(), "repetir el mismo paso no es un error");
        assertEquals("preparando", json(repetido).get("estado").asText());
        assertEquals(preparandoEn, escenario.texto(r.tenantId(), "select preparando_en::text from pedido_items where id = ?", item),
                "y no vuelve a marcar el tiempo");
        // Un item servido no vuelve atras ni se anula.
        post(cocina, "/api/v1/cocina/items/" + item + "/listo");
        post(mesero, "/api/v1/cocina/items/" + item + "/servido");
        assertCodigo(409, "TRANSICION_INVALIDA", post(cocina, "/api/v1/cocina/items/" + item + "/preparando"));
        assertCodigo(409, "TRANSICION_INVALIDA", post(escenario.token(r, "cajero"), "/api/v1/cocina/items/" + item + "/anular", Map.of("motivo", "x")));
    }

    // ------------------------------------------------------------ anular items

    @Test
    void anularUnItemBajaElTotalYLaFacturaSoloIncluyeLoVigente() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID postre = escenario.crearProducto(r.tenantId(), "Helado", "10.00");
        String cajero = escenario.token(r, "cajero");
        UUID pedidoId = pedido(r, cajero, r.productoId(), "1", postre, "1");     // 20.00 + IVA 3.00 = 23.00
        UUID helado = item(r, pedidoId, "Helado");
        confirmarYEnviar(cajero, pedidoId);

        assertEquals(400, post(cajero, "/api/v1/cocina/items/" + helado + "/anular", Map.of("motivo", "")).statusCode(), "el motivo es obligatorio");
        Response anulado = post(cajero, "/api/v1/cocina/items/" + helado + "/anular", Map.of("motivo", "El cliente se arrepintio"));

        assertEquals(200, anulado.statusCode(), anulado.asString());
        assertEquals("anulado", json(anulado).get("estado").asText());
        assertEquals("El cliente se arrepintio", escenario.texto(r.tenantId(), "select motivo_anulacion from pedido_items where id = ?", helado));
        JsonNode pedido = json(get(cajero, "/api/v1/pedidos/" + pedidoId));
        assertEquals(0, new BigDecimal("10.00").compareTo(pedido.get("subtotal").decimalValue()));
        assertEquals(0, new BigDecimal("1.50").compareTo(pedido.get("iva").decimalValue()));
        assertEquals(0, new BigDecimal("11.50").compareTo(pedido.get("total").decimalValue()));
        assertEquals(1, pedido.get("items").size(), "la respuesta solo lista lo vigente");

        // Se cobra lo que queda y la factura sale solo con el item vigente.
        cobrarTodo(cajero, pedidoId, "11.50");
        drenar();
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from comprobante_detalles d join comprobantes c on c.id = d.comprobante_id "
                + "where c.pedido_id = ?", pedidoId));
        assertEquals("11.50", escenario.texto(r.tenantId(), "select importe_total::text from comprobantes where pedido_id = ?", pedidoId));
    }

    @Test
    void conCobrosNoSePuedeAnularUnItem() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID postre = escenario.crearProducto(r.tenantId(), "Helado", "10.00");
        String cajero = escenario.token(r, "cajero");
        UUID pedidoId = pedido(r, cajero, r.productoId(), "1", postre, "1");
        UUID helado = item(r, pedidoId, "Helado");
        confirmarYEnviar(cajero, pedidoId);
        post(cajero, "/api/v1/pagos", Map.of("id", UuidV7.next().toString(), "pedidoId", pedidoId.toString(),
                "metodo", "efectivo", "monto", new BigDecimal("5.00")));

        assertCodigo(409, "PEDIDO_CON_COBROS", post(cajero, "/api/v1/cocina/items/" + helado + "/anular", Map.of("motivo", "x")));
        assertEquals("enviado", estadoItem(r, helado), "no cambio nada");
    }

    @Test
    void anularTodosLosItemsCancelaElPedido() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String cajero = escenario.token(r, "cajero");
        UUID pedidoId = pedido(r, cajero, r.productoId(), "1");
        UUID item = item(r, pedidoId, "Encebollado");
        confirmarYEnviar(cajero, pedidoId);

        assertEquals(200, post(cajero, "/api/v1/cocina/items/" + item + "/anular", Map.of("motivo", "Se acabo")).statusCode());

        assertEquals("cancelado", estadoPedido(r, pedidoId));
        assertEquals(1, eventos(r, "PedidoCancelado", pedidoId));
        assertEquals("->creado,creado>confirmado,confirmado>cancelado", historial(r, pedidoId));
    }

    // ------------------------------------------------------------- cancelar

    @Test
    void cancelarUnPedidoSinCobrosAnulaSusItemsPorEvento() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cajero = escenario.token(r, "cajero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "2");
        UUID item = item(r, pedidoId, "Encebollado");
        confirmarYEnviar(mesero, pedidoId);

        assertEquals(400, post(cajero, "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", " ")).statusCode(), "motivo obligatorio");
        Response cancelado = post(cajero, "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "Mesa se fue"));

        assertEquals(200, cancelado.statusCode(), cancelado.asString());
        assertEquals("cancelado", json(cancelado).get("estado").asText());
        assertEquals("Mesa se fue", escenario.texto(r.tenantId(),
                "select motivo from pedido_historial where pedido_id = ? and estado_nuevo = 'cancelado'", pedidoId));
        assertEquals(1, eventos(r, "PedidoCancelado", pedidoId));
        assertEquals(0, get(cocina, "/api/v1/cocina/items").jsonPath().getList("$").size(),
                "la pantalla ya no lo muestra (los pedidos cancelados se filtran aunque el evento tarde)");

        drenar();
        assertEquals("anulado", estadoItem(r, item), "cocina anula los items al recibir el evento");
        assertEquals("Mesa se fue", escenario.texto(r.tenantId(), "select motivo_anulacion from pedido_items where id = ?", item));
        assertCodigo(409, "PEDIDO_NO_ACTIVO", post(cocina, "/api/v1/cocina/items/" + item + "/preparando"));
        assertCodigo(409, "PEDIDO_CANCELADO", post(mesero, "/api/v1/pedidos/" + pedidoId + "/confirmar"));
    }

    @Test
    void cancelarEsIdempotenteYNoRepiteElEvento() {
        Restaurante r = conParrilla();
        String cajero = escenario.token(r, "cajero");
        UUID pedidoId = pedido(r, cajero, r.productoId(), "1");

        assertEquals(200, post(cajero, "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "Error al tomar la orden")).statusCode());
        assertEquals(200, post(cajero, "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "Error al tomar la orden")).statusCode());

        assertEquals(1, eventos(r, "PedidoCancelado", pedidoId));
        assertEquals("->creado,creado>cancelado", historial(r, pedidoId), "se puede cancelar sin haberlo confirmado");
    }

    @Test
    void noSePuedeCancelarConCobrosNiDespuesDeEntregar() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cajero = escenario.token(r, "cajero");
        String cocina = escenario.token(r, "cocina");

        UUID conCobro = pedido(r, mesero, r.productoId(), "1");
        confirmarYEnviar(mesero, conCobro);
        cobrarTodo(cajero, conCobro, "11.50");
        assertCodigo(409, "PEDIDO_CON_COBROS", post(cajero, "/api/v1/pedidos/" + conCobro + "/cancelar", Map.of("motivo", "x")));

        UUID entregado = pedido(r, mesero, r.productoId(), "1");
        UUID item = item(r, entregado, "Encebollado");
        confirmarYEnviar(mesero, entregado);
        post(cocina, "/api/v1/cocina/items/" + item + "/listo");
        post(mesero, "/api/v1/cocina/items/" + item + "/servido");
        assertEquals("entregado", estadoPedido(r, entregado));
        assertCodigo(409, "TRANSICION_INVALIDA", post(cajero, "/api/v1/pedidos/" + entregado + "/cancelar", Map.of("motivo", "x")));
        assertEquals("entregado", estadoPedido(r, entregado));
    }

    // ------------------------------------------------- aislamiento y permisos

    @Test
    void otroRestauranteNoVeNiToca() {
        Restaurante a = conParrilla();
        Restaurante b = conParrilla();
        String meseroA = escenario.token(a, "mesero");
        String cocinaB = escenario.token(b, "cocina");
        String meseroB = escenario.token(b, "mesero");
        UUID pedidoA = pedido(a, meseroA, a.productoId(), "1");
        UUID itemA = item(a, pedidoA, "Encebollado");
        confirmarYEnviar(meseroA, pedidoA);

        assertEquals(0, get(cocinaB, "/api/v1/cocina/items").jsonPath().getList("$").size());
        assertCodigo(404, "ITEM_NO_ENCONTRADO", post(cocinaB, "/api/v1/cocina/items/" + itemA + "/listo"));
        assertCodigo(404, "PEDIDO_NO_ENCONTRADO", post(meseroB, "/api/v1/pedidos/" + pedidoA + "/confirmar"));
        assertEquals("enviado", estadoItem(a, itemA), "nada cambio");
    }

    @Test
    void otraSucursalDelMismoRestauranteNoVeNiToca() {
        Restaurante r = conParrilla();
        UUID otraSucursal = escenario.crearSucursal(r.tenantId(), "002");
        String mesero = escenario.token(r, "mesero");
        String cocinaOtra = escenario.tokenEnSucursal(r, otraSucursal, "cocina");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1");
        UUID item = item(r, pedidoId, "Encebollado");
        confirmarYEnviar(mesero, pedidoId);

        assertEquals(0, get(cocinaOtra, "/api/v1/cocina/items").jsonPath().getList("$").size(), "cada sucursal ve su cocina");
        assertCodigo(404, "ITEM_NO_ENCONTRADO", post(cocinaOtra, "/api/v1/cocina/items/" + item + "/preparando"));
        assertEquals("enviado", estadoItem(r, item));
    }

    @Test
    void cadaRolSoloHaceLoSuyo() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        String repartidor = escenario.token(r, "repartidor");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1");
        UUID item = item(r, pedidoId, "Encebollado");
        confirmarYEnviar(mesero, pedidoId);

        assertEquals(403, post(mesero, "/api/v1/cocina/items/" + item + "/preparando").statusCode(), "el mesero no cocina");
        assertEquals(403, post(mesero, "/api/v1/cocina/items/" + item + "/listo").statusCode());
        assertEquals(403, post(cocina, "/api/v1/cocina/items/" + item + "/anular", Map.of("motivo", "x")).statusCode(), "cocina no anula");
        assertEquals(403, post(cocina, "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "x")).statusCode());
        assertEquals(403, post(cocina, "/api/v1/pedidos/" + pedidoId + "/confirmar").statusCode(), "cocina no confirma pedidos");
        assertEquals(403, post(mesero, "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "x")).statusCode(),
                "cancelar exige caja o administracion");
        assertEquals(403, get(repartidor, "/api/v1/cocina/items").statusCode());
        assertEquals(200, get(cocina, "/api/v1/cocina/items").statusCode());
        assertEquals(401, given().when().get("/api/v1/cocina/items").statusCode());
        assertEquals(401, given().contentType(ContentType.JSON).body("{}").when().post("/api/v1/cocina/items/" + item + "/listo").statusCode());
        assertEquals("enviado", estadoItem(r, item), "ninguno de los rechazados cambio nada");
    }

    // -------------------------------------------------------- reentrega de eventos

    @Test
    void laReentregaDelEventoNoReiniciaLosItems() {
        Restaurante r = conParrilla();
        String mesero = escenario.token(r, "mesero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = pedido(r, mesero, r.productoId(), "1");
        UUID item = item(r, pedidoId, "Encebollado");
        confirmarYEnviar(mesero, pedidoId);
        post(cocina, "/api/v1/cocina/items/" + item + "/preparando");
        String enviadoEn = escenario.texto(r.tenantId(), "select enviado_en::text from pedido_items where id = ?", item);

        // El relay murio antes de confirmar la marca de publicado: el evento vuelve a quedar pendiente.
        admin.ejecutar("update outbox set publicado_en = null where agregado_id = ? and tipo = 'PedidoConfirmado'", pedidoId);
        drenar();

        assertEquals("preparando", estadoItem(r, item), "no se devuelve a 'enviado'");
        assertEquals(enviadoEn, escenario.texto(r.tenantId(), "select enviado_en::text from pedido_items where id = ?", item));
        assertEquals("en_preparacion", estadoPedido(r, pedidoId));
        assertTrue(escenario.contar(r.tenantId(), "select count(*) from outbox where agregado_id = ? and tipo = 'PedidoConfirmado' and publicado_en is not null", pedidoId) == 1);
    }

    @Test
    void paraLlevarSigueElMismoFlujo() {
        Restaurante r = conParrilla();
        String cajero = escenario.token(r, "cajero");
        String cocina = escenario.token(r, "cocina");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, post(cajero, "/api/v1/pedidos", Map.of("id", pedidoId.toString(), "canal", "para_llevar",
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("1"))))).statusCode());
        UUID item = item(r, pedidoId, "Encebollado");
        cobrarTodo(cajero, pedidoId, "11.50");    // pago anticipado
        confirmarYEnviar(cajero, pedidoId);

        post(cocina, "/api/v1/cocina/items/" + item + "/listo");
        post(cajero, "/api/v1/cocina/items/" + item + "/servido");

        assertEquals("cerrado", estadoPedido(r, pedidoId), "pagado por adelantado y entregado: se cierra");
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos where id = ? and canal = 'para_llevar' and mesa_id is null", pedidoId));
    }
}
