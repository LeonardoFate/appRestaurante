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
 * Reparto de extremo a extremo: pedido a domicilio -> cocina lo deja listo -> nace la entrega -> se asigna repartidor ->
 * recoge -> entrega y cobra en efectivo -> caja liquida. Contra la base real, con JWT firmados.
 */
@QuarkusTest
class EntregasFlujoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOTAL = "14.00";   // 10.00 + 1.50 IVA + 2.50 de envio

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

    private String estadoPedido(Restaurante r, UUID pedidoId) {
        return escenario.texto(r.tenantId(), "select estado from pedidos where id = ?", pedidoId);
    }

    private String estadoEntrega(Restaurante r, UUID pedidoId) {
        return escenario.texto(r.tenantId(), "select estado from entregas where pedido_id = ?", pedidoId);
    }

    /** Restaurante con zona (envio 2.50) y un cliente con direccion; devuelve tambien su zona. */
    private record Local(Restaurante r, UUID zona, UUID cliente, UUID direccion) {
    }

    private Local local() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.50", "0");
        String caja = escenario.token(r, "cajero");
        UUID cliente = UuidV7.next();
        String cedula = Identificaciones.cedulaAleatoria();
        assertEquals(201, post(caja, "/api/v1/delivery/clientes", Map.of("id", cliente.toString(), "nombre", "Ana Perez",
                "tipoIdentificacion", "05", "identificacion", cedula, "telefono", "0991234567")).statusCode());
        UUID direccion = UuidV7.next();
        assertEquals(201, post(caja, "/api/v1/delivery/clientes/" + cliente + "/direcciones",
                Map.of("id", direccion.toString(), "direccion", "Av. Amazonas N23", "referencia", "casa azul")).statusCode());
        return new Local(r, zona, cliente, direccion);
    }

    /** Toma un pedido de 1 unidad, lo confirma y lo deja listo en cocina SIN procesar aun el evento de listo. */
    private UUID pedidoListoSinEvento(Local l) {
        Restaurante r = l.r();
        UUID pedidoId = UuidV7.next();
        Response creado = post(escenario.token(r, "cajero"), "/api/v1/delivery/pedidos", Map.of("id", pedidoId.toString(),
                "clienteId", l.cliente().toString(), "direccionId", l.direccion().toString(), "zonaId", l.zona().toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", 1))));
        assertEquals(201, creado.statusCode(), creado.asString());
        assertEquals(200, post(escenario.token(r, "cajero"), "/api/v1/pedidos/" + pedidoId + "/confirmar").statusCode());
        drenar();   // cocina recibe los items
        UUID item = UUID.fromString(escenario.texto(r.tenantId(), "select id::text from pedido_items where pedido_id = ?", pedidoId));
        assertEquals(200, post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + item + "/listo").statusCode());
        assertEquals("listo", estadoPedido(r, pedidoId));
        return pedidoId;
    }

    /** Pedido listo y con su entrega ya creada (pendiente de asignar). */
    private UUID pedidoConEntrega(Local l) {
        UUID pedidoId = pedidoListoSinEvento(l);
        drenar();
        assertEquals("pendiente", estadoEntrega(l.r(), pedidoId));
        return pedidoId;
    }

    private Response asignar(Restaurante r, UUID pedidoId, UUID repartidor) {
        return post(escenario.token(r, "cajero"), "/api/v1/delivery/entregas/" + pedidoId + "/asignar",
                Map.of("repartidorId", repartidor.toString()));
    }

    private Response comoRepartidor(Restaurante r, UUID repartidor, String accion, UUID pedidoId) {
        return post(escenario.tokenDe(r, repartidor, "repartidor"), "/api/v1/delivery/entregas/" + pedidoId + "/" + accion);
    }

    // ---------------------------------------------------------- nace la entrega

    @Test
    void laEntregaNaceSoloCuandoElPedidoQuedaListoYSoloEnDelivery() {
        Local l = local();
        UUID pedidoId = pedidoListoSinEvento(l);
        assertEquals(0, escenario.contar(l.r().tenantId(), "select count(*) from entregas"), "el evento aun no se proceso");

        drenar();
        drenar();   // reentrega: no duplica

        assertEquals("pendiente", estadoEntrega(l.r(), pedidoId));
        assertEquals(1, escenario.contar(l.r().tenantId(), "select count(*) from entregas"));

        // Un pedido de salon que queda listo no genera entrega.
        String caja = escenario.token(l.r(), "cajero");
        UUID salon = UuidV7.next();
        post(caja, "/api/v1/pedidos", Map.of("id", salon.toString(), "canal", "salon", "mesaId", l.r().mesaId().toString(),
                "items", List.of(Map.of("productoId", l.r().productoId().toString(), "cantidad", 1))));
        post(caja, "/api/v1/pedidos/" + salon + "/confirmar");
        drenar();
        UUID item = UUID.fromString(escenario.texto(l.r().tenantId(), "select id::text from pedido_items where pedido_id = ?", salon));
        post(escenario.token(l.r(), "cocina"), "/api/v1/cocina/items/" + item + "/listo");
        drenar();
        assertEquals(1, escenario.contar(l.r().tenantId(), "select count(*) from entregas"), "solo la del delivery");
    }

    @Test
    void unPedidoCanceladoAntesDeProcesarElEventoNoCreaEntrega() {
        Local l = local();
        UUID pedidoId = pedidoListoSinEvento(l);
        assertEquals(200, post(escenario.token(l.r(), "admin"), "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "x")).statusCode());

        drenar();

        assertEquals(0, escenario.contar(l.r().tenantId(), "select count(*) from entregas"));
    }

    // ------------------------------------------------------- flujo completo

    @Test
    void contraEntregaElRepartidorCobraYCajaLiquida() {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);

        Response asignada = asignar(r, pedidoId, repartidor);
        assertEquals(200, asignada.statusCode(), asignada.asString());
        assertEquals("asignado", json(asignada).get("estado").asText());
        assertEquals("asignado", estadoPedido(r, pedidoId));

        Response recogida = comoRepartidor(r, repartidor, "recoger", pedidoId);
        assertEquals(200, recogida.statusCode(), recogida.asString());
        assertEquals("recogido", json(recogida).get("estado").asText());
        assertEquals("en_camino", estadoPedido(r, pedidoId));

        Response entregada = comoRepartidor(r, repartidor, "entregar", pedidoId);
        assertEquals(200, entregada.statusCode(), entregada.asString());
        assertEquals("entregado", json(entregada).get("estado").asText());
        assertEquals(0, new BigDecimal(TOTAL).compareTo(json(entregada).get("efectivoPorCobrar").decimalValue()));
        assertEquals("cerrado", estadoPedido(r, pedidoId), "entregado y pagado se cierra solo");
        assertEquals("pagado", escenario.texto(r.tenantId(), "select estado_pago from pedidos where id = ?", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pagos where pedido_id = ? and metodo = 'efectivo' and monto = ?",
                pedidoId, new BigDecimal(TOTAL)));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedido_historial where pedido_id = ? and estado_nuevo = 'entregado'", pedidoId));

        // El cobro dispara la factura del pedido.
        drenar();
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from comprobantes where pedido_id = ? and tipo = '01'", pedidoId));

        // Caja ve el efectivo pendiente y lo liquida; repetir no cuenta dos veces.
        String caja = escenario.token(r, "cajero");
        Response pendientes = get(caja, "/api/v1/delivery/liquidaciones/pendientes");
        assertEquals(1, json(pendientes).size());
        assertEquals(repartidor.toString(), json(pendientes).get(0).get("repartidorId").asText());
        assertEquals(0, new BigDecimal(TOTAL).compareTo(json(pendientes).get(0).get("efectivo").decimalValue()));

        Response liquidada = post(caja, "/api/v1/delivery/liquidaciones", Map.of("repartidorId", repartidor.toString()));
        assertEquals(200, liquidada.statusCode(), liquidada.asString());
        assertEquals(1, json(liquidada).get("entregas").asInt());
        assertEquals(0, new BigDecimal(TOTAL).compareTo(json(liquidada).get("efectivo").decimalValue()));
        Response otraVez = post(caja, "/api/v1/delivery/liquidaciones", Map.of("repartidorId", repartidor.toString()));
        assertEquals(0, json(otraVez).get("entregas").asInt());
        assertEquals(0, BigDecimal.ZERO.compareTo(json(otraVez).get("efectivo").decimalValue()));
        assertEquals(0, json(get(caja, "/api/v1/delivery/liquidaciones/pendientes")).size());
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from auditoria where accion = 'delivery.liquidar'"),
                "solo la liquidacion con efectivo queda auditada");
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from entregas where efectivo_liquidado_en is not null and liquidado_por = ?",
                r.usuarioId()));
    }

    @Test
    void siYaEstabaPagadoElRepartidorNoCobraNada() {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        assertEquals(201, post(escenario.token(r, "cajero"), "/api/v1/pagos", Map.of("id", UuidV7.next().toString(),
                "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal(TOTAL))).statusCode());
        asignar(r, pedidoId, repartidor);
        comoRepartidor(r, repartidor, "recoger", pedidoId);

        Response entregada = comoRepartidor(r, repartidor, "entregar", pedidoId);

        assertEquals(200, entregada.statusCode(), entregada.asString());
        assertEquals(0, BigDecimal.ZERO.compareTo(json(entregada).get("efectivoPorCobrar").decimalValue()));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pagos where pedido_id = ?", pedidoId), "no se cobro dos veces");
        assertEquals("cerrado", estadoPedido(r, pedidoId));
        assertEquals(0, json(get(escenario.token(r, "cajero"), "/api/v1/delivery/liquidaciones/pendientes")).size());
    }

    @Test
    void unPedidoAnticipadoSinPagarNoSaleAReparto() {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = UuidV7.next();
        post(escenario.token(r, "cajero"), "/api/v1/delivery/pedidos", Map.of("id", pedidoId.toString(), "momentoPago", "anticipado",
                "clienteId", l.cliente().toString(), "direccionId", l.direccion().toString(), "zonaId", l.zona().toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", 1))));
        post(escenario.token(r, "cajero"), "/api/v1/pedidos/" + pedidoId + "/confirmar");
        drenar();
        UUID item = UUID.fromString(escenario.texto(r.tenantId(), "select id::text from pedido_items where pedido_id = ?", pedidoId));
        post(escenario.token(r, "cocina"), "/api/v1/cocina/items/" + item + "/listo");
        drenar();
        assertEquals(200, asignar(r, pedidoId, repartidor).statusCode(), "se puede asignar mientras se espera el pago");

        assertCodigo(409, "PAGO_ANTICIPADO_PENDIENTE", comoRepartidor(r, repartidor, "recoger", pedidoId));
        assertEquals("asignado", estadoPedido(r, pedidoId));

        assertEquals(201, post(escenario.token(r, "cajero"), "/api/v1/pagos", Map.of("id", UuidV7.next().toString(),
                "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal(TOTAL))).statusCode());
        assertEquals(200, comoRepartidor(r, repartidor, "recoger", pedidoId).statusCode(), "ya pagado: puede salir");
        assertEquals("en_camino", estadoPedido(r, pedidoId));
    }

    @Test
    void recogerYEntregarRepetidosSonIdempotentes() {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        asignar(r, pedidoId, repartidor);

        assertEquals(200, comoRepartidor(r, repartidor, "recoger", pedidoId).statusCode());
        assertEquals(200, comoRepartidor(r, repartidor, "recoger", pedidoId).statusCode());
        assertEquals(200, comoRepartidor(r, repartidor, "entregar", pedidoId).statusCode());
        assertEquals(200, comoRepartidor(r, repartidor, "entregar", pedidoId).statusCode());

        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pagos where pedido_id = ?", pedidoId));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedido_historial where pedido_id = ? and estado_nuevo = 'en_camino'", pedidoId));
    }

    // ------------------------------------------------------------ asignacion

    @Test
    void soloSeAsignaUnRepartidorActivoDeLaSucursal() {
        Local l = local();
        Restaurante r = l.r();
        UUID pedidoId = pedidoConEntrega(l);
        UUID mesero = escenario.crearUsuario(r, "mesero-" + UuidV7.next() + "@prueba.com", "clave-larga-123");
        escenario.darMembresia(r.tenantId(), mesero, null, "mesero");
        UUID suspendido = escenario.crearRepartidor(r);
        escenario.ejecutar(r.tenantId(), "update membresias set estado = 'suspendida' where usuario_id = ?", suspendido);
        UUID sinMembresia = escenario.crearUsuario(r, "x-" + UuidV7.next() + "@prueba.com", "clave-larga-123");
        UUID otraSucursal = escenario.crearSucursal(r.tenantId(), "002");
        UUID deOtraSucursal = escenario.crearUsuario(r, "y-" + UuidV7.next() + "@prueba.com", "clave-larga-123");
        escenario.darMembresia(r.tenantId(), deOtraSucursal, otraSucursal, "repartidor");

        for (UUID malo : List.of(mesero, suspendido, sinMembresia, deOtraSucursal, UuidV7.next())) {
            assertCodigo(422, "REPARTIDOR_INVALIDO", asignar(r, pedidoId, malo));
        }
        assertEquals("pendiente", estadoEntrega(r, pedidoId));
        assertEquals("listo", estadoPedido(r, pedidoId));
    }

    @Test
    void sePuedeReasignarAntesDeRecogerPeroNoDespues() {
        Local l = local();
        Restaurante r = l.r();
        UUID uno = escenario.crearRepartidor(r);
        UUID otro = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);

        assertEquals(200, asignar(r, pedidoId, uno).statusCode());
        assertEquals(200, asignar(r, pedidoId, otro).statusCode(), "reasignar antes de recoger");
        assertEquals(otro.toString(), escenario.texto(r.tenantId(), "select repartidor_id::text from entregas where pedido_id = ?", pedidoId));
        assertEquals("asignado", estadoPedido(r, pedidoId));

        assertCodigo(403, "ENTREGA_AJENA", comoRepartidor(r, uno, "recoger", pedidoId));
        assertEquals(200, comoRepartidor(r, otro, "recoger", pedidoId).statusCode());
        assertCodigo(409, "ENTREGA_TRANSICION_INVALIDA", asignar(r, pedidoId, uno));
    }

    @Test
    void sinEntregaNoSePuedeAsignarNiOperar() {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoListoSinEntrega = pedidoListoSinEvento(l);   // el evento aun no se proceso

        assertCodigo(404, "ENTREGA_NO_ENCONTRADA", asignar(r, pedidoListoSinEntrega, repartidor));
        assertCodigo(404, "ENTREGA_NO_ENCONTRADA", asignar(r, UuidV7.next(), repartidor));
        assertCodigo(404, "ENTREGA_NO_ENCONTRADA", comoRepartidor(r, repartidor, "recoger", UuidV7.next()));
    }

    // -------------------------------------------------------------- seguridad

    @Test
    void cadaRolHaceLoSuyo() {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        String ruta = "/api/v1/delivery/entregas/" + pedidoId;

        for (String rol : List.of("mesero", "cocina", "repartidor")) {
            assertEquals(403, post(escenario.token(r, rol), ruta + "/asignar", Map.of("repartidorId", repartidor.toString())).statusCode(), rol + " no asigna");
        }
        asignar(r, pedidoId, repartidor);
        for (String rol : List.of("cajero", "admin", "owner", "mesero", "cocina")) {
            assertEquals(403, post(escenario.token(r, rol), ruta + "/recoger").statusCode(), rol + " no recoge");
            assertEquals(403, post(escenario.token(r, rol), ruta + "/entregar").statusCode(), rol + " no entrega");
        }
        assertEquals(403, get(escenario.token(r, "mesero"), "/api/v1/delivery/entregas").statusCode());
        assertEquals(403, get(escenario.token(r, "repartidor"), "/api/v1/delivery/entregas").statusCode(), "el repartidor no ve la lista general");
        assertEquals(403, get(escenario.token(r, "repartidor"), "/api/v1/delivery/liquidaciones/pendientes").statusCode());
        assertEquals(403, post(escenario.token(r, "repartidor"), "/api/v1/delivery/liquidaciones", Map.of("repartidorId", repartidor.toString())).statusCode());
        given().contentType(ContentType.JSON).body("{}").when().post(ruta + "/recoger").then().statusCode(401);
    }

    @Test
    void unRepartidorNoTocaLaEntregaDeOtro() {
        Local l = local();
        Restaurante r = l.r();
        UUID mio = escenario.crearRepartidor(r);
        UUID ajeno = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        asignar(r, pedidoId, mio);

        assertCodigo(403, "ENTREGA_AJENA", comoRepartidor(r, ajeno, "recoger", pedidoId));
        comoRepartidor(r, mio, "recoger", pedidoId);
        assertCodigo(403, "ENTREGA_AJENA", comoRepartidor(r, ajeno, "entregar", pedidoId));
        assertCodigo(403, "ENTREGA_AJENA", post(escenario.tokenDe(r, ajeno, "repartidor"),
                "/api/v1/delivery/entregas/" + pedidoId + "/fallar", Map.of("motivo", "x")));
        assertEquals("recogido", estadoEntrega(r, pedidoId));
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from pagos"));
    }

    @Test
    void otroRestauranteNoVeNiToca() {
        Local l = local();
        Restaurante intruso = escenario.crearRestaurante("10.00");
        UUID repartidor = escenario.crearRepartidor(l.r());
        UUID pedidoId = pedidoConEntrega(l);
        UUID suyo = escenario.crearRepartidor(intruso);

        assertCodigo(404, "ENTREGA_NO_ENCONTRADA", asignar(intruso, pedidoId, suyo));
        assertEquals(0, json(get(escenario.token(intruso, "cajero"), "/api/v1/delivery/entregas")).size());
        assertCodigo(404, "ENTREGA_NO_ENCONTRADA", comoRepartidor(intruso, suyo, "recoger", pedidoId));
        assertEquals("pendiente", estadoEntrega(l.r(), pedidoId));
    }

    @Test
    void otraSucursalDelMismoRestauranteNoVeLaEntrega() {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        UUID otraSucursal = escenario.crearSucursal(r.tenantId(), "002");
        String cajaDeOtra = escenario.tokenEnSucursal(r, otraSucursal, "cajero");

        assertEquals(0, json(get(cajaDeOtra, "/api/v1/delivery/entregas")).size());
        assertCodigo(404, "ENTREGA_NO_ENCONTRADA", post(cajaDeOtra, "/api/v1/delivery/entregas/" + pedidoId + "/asignar",
                Map.of("repartidorId", repartidor.toString())));
    }

    // ---------------------------------------------------------------- fallos

    @Test
    void elRepartidorPuedeReportarQueNoPudoEntregar() {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        asignar(r, pedidoId, repartidor);
        String token = escenario.tokenDe(r, repartidor, "repartidor");
        String ruta = "/api/v1/delivery/entregas/" + pedidoId + "/fallar";

        assertCodigo(409, "ENTREGA_TRANSICION_INVALIDA", post(token, ruta, Map.of("motivo", "no estaba")));   // aun no recogio
        comoRepartidor(r, repartidor, "recoger", pedidoId);
        assertEquals(400, post(token, ruta, Map.of("motivo", " ")).statusCode(), "motivo obligatorio");

        Response res = post(token, ruta, Map.of("motivo", "nadie en casa"));
        assertEquals(200, res.statusCode(), res.asString());
        assertEquals("fallido", json(res).get("estado").asText());
        assertEquals("nadie en casa", escenario.texto(r.tenantId(), "select motivo_fallo from entregas where pedido_id = ?", pedidoId));
        assertEquals("en_camino", estadoPedido(r, pedidoId), "el despacho decide que hacer con el pedido");
        assertEquals(200, post(token, ruta, Map.of("motivo", "nadie en casa")).statusCode(), "repetir es idempotente");
        assertCodigo(409, "ENTREGA_TRANSICION_INVALIDA", comoRepartidor(r, repartidor, "entregar", pedidoId));
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from pagos"));
    }

    @Test
    void cancelarElPedidoConLaEntregaEnCursoLaDejaFallida() {
        Local l = local();
        Restaurante r = l.r();
        UUID repartidor = escenario.crearRepartidor(r);
        UUID pedidoId = pedidoConEntrega(l);
        asignar(r, pedidoId, repartidor);

        assertEquals(200, post(escenario.token(r, "admin"), "/api/v1/pedidos/" + pedidoId + "/cancelar", Map.of("motivo", "cliente llamo")).statusCode());
        drenar();

        assertEquals("fallido", estadoEntrega(r, pedidoId));
        assertEquals("Pedido cancelado: cliente llamo", escenario.texto(r.tenantId(), "select motivo_fallo from entregas where pedido_id = ?", pedidoId));
        assertCodigo(409, "ENTREGA_TRANSICION_INVALIDA", comoRepartidor(r, repartidor, "recoger", pedidoId));
    }

    // --------------------------------------------------------------- listados

    @Test
    void cadaRepartidorVeSoloSusEntregasConLosDatosParaLlegar() {
        Local l = local();
        Restaurante r = l.r();
        UUID uno = escenario.crearRepartidor(r);
        UUID otro = escenario.crearRepartidor(r);
        UUID p1 = pedidoConEntrega(l);
        UUID p2 = pedidoConEntrega(l);
        asignar(r, p1, uno);
        asignar(r, p2, otro);

        Response mias = get(escenario.tokenDe(r, uno, "repartidor"), "/api/v1/delivery/entregas/mias");
        assertEquals(200, mias.statusCode());
        assertEquals(1, json(mias).size());
        JsonNode e = json(mias).get(0);
        assertEquals(p1.toString(), e.get("pedidoId").asText());
        assertEquals("Ana Perez", e.get("cliente").asText());
        assertEquals("0991234567", e.get("telefono").asText());
        assertEquals("Av. Amazonas N23", e.get("direccion").asText());
        assertEquals("casa azul", e.get("referencia").asText());

        // El despacho ve todas las que requieren atencion y puede filtrar.
        String caja = escenario.token(r, "cajero");
        assertEquals(2, json(get(caja, "/api/v1/delivery/entregas")).size());
        assertEquals(2, json(get(caja, "/api/v1/delivery/entregas?estado=asignado")).size());
        assertEquals(0, json(get(caja, "/api/v1/delivery/entregas?estado=entregado")).size());
        assertCodigo(422, "ESTADO_INVALIDO", get(caja, "/api/v1/delivery/entregas?estado=volando"));
    }
}
