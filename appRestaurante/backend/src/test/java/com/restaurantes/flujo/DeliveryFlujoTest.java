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
 * Delivery, primera etapa: clientes, direcciones, zonas y la toma del pedido a domicilio con su costo de envio, hasta
 * la factura coherente. Contra la base real, con JWT firmados.
 */
@QuarkusTest
class DeliveryFlujoTest {

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

    private static String cedulaAleatoria() {
        return Identificaciones.cedulaAleatoria();
    }

    private Map<String, Object> clienteBody(UUID id, String identificacion) {
        return Map.of("id", id.toString(), "nombre", "Ana Perez", "tipoIdentificacion", "05",
                "identificacion", identificacion, "telefono", "0991234567");
    }

    /** Crea un cliente con una direccion; devuelve {clienteId, direccionId}. */
    private UUID[] clienteConDireccion(Restaurante r) {
        String token = escenario.token(r, "cajero");
        UUID clienteId = UuidV7.next();
        assertEquals(201, post(token, "/api/v1/delivery/clientes", clienteBody(clienteId, cedulaAleatoria())).statusCode());
        UUID direccionId = UuidV7.next();
        assertEquals(201, post(token, "/api/v1/delivery/clientes/" + clienteId + "/direcciones",
                Map.of("id", direccionId.toString(), "direccion", "Av. Amazonas N23", "referencia", "casa azul")).statusCode());
        return new UUID[] {clienteId, direccionId};
    }

    private Map<String, Object> pedidoBody(UUID pedidoId, UUID[] cliente, UUID zonaId, Restaurante r, String cantidad, String propina) {
        return Map.of("id", pedidoId.toString(), "clienteId", cliente[0].toString(), "direccionId", cliente[1].toString(),
                "zonaId", zonaId.toString(), "propina", new BigDecimal(propina),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal(cantidad))));
    }

    // ---------------------------------------------------------- clientes

    @Test
    void crearClienteEsIdempotenteYNoPermiteDuplicarLaIdentificacion() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID id = UuidV7.next();
        String cedula = cedulaAleatoria();

        assertEquals(201, post(token, "/api/v1/delivery/clientes", clienteBody(id, cedula)).statusCode());
        assertEquals(200, post(token, "/api/v1/delivery/clientes", clienteBody(id, cedula)).statusCode(), "misma orden: idempotente");
        assertCodigo(409, "CLIENTE_ID_REUTILIZADO", post(token, "/api/v1/delivery/clientes", clienteBody(id, cedulaAleatoria())));
        assertCodigo(409, "CLIENTE_YA_EXISTE", post(token, "/api/v1/delivery/clientes", clienteBody(UuidV7.next(), cedula)));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from clientes"));
    }

    @Test
    void validaLaIdentificacionYLosDatosObligatorios() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");

        assertCodigo(422, "CEDULA_INVALIDA", post(token, "/api/v1/delivery/clientes", clienteBody(UuidV7.next(), "12345")));
        assertCodigo(422, "CEDULA_INVALIDA", post(token, "/api/v1/delivery/clientes", clienteBody(UuidV7.next(), "1710034061")));   // verificador malo
        assertCodigo(422, "IDENTIFICACION_TIPO_INVALIDO", post(token, "/api/v1/delivery/clientes",
                Map.of("nombre", "X", "tipoIdentificacion", "07", "identificacion", "9999999999999", "telefono", "099")));
        assertCodigo(422, "RUC_INVALIDO", post(token, "/api/v1/delivery/clientes",
                Map.of("nombre", "X", "tipoIdentificacion", "04", "identificacion", "123", "telefono", "099")));
        assertEquals(400, post(token, "/api/v1/delivery/clientes",
                Map.of("nombre", "X", "tipoIdentificacion", "05", "identificacion", cedulaAleatoria())).statusCode(), "sin telefono");
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from clientes"));
    }

    @Test
    void lasDireccionesSeAgreganDeFormaIdempotenteYSeVenEnElCliente() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID[] ids = clienteConDireccion(r);

        Map<String, Object> igual = Map.of("id", ids[1].toString(), "direccion", "Av. Amazonas N23", "referencia", "casa azul");
        assertEquals(200, post(token, "/api/v1/delivery/clientes/" + ids[0] + "/direcciones", igual).statusCode());
        assertEquals(201, post(token, "/api/v1/delivery/clientes/" + ids[0] + "/direcciones",
                Map.of("etiqueta", "Oficina", "direccion", "Calle 2")).statusCode());

        Response cliente = get(token, "/api/v1/delivery/clientes/" + ids[0]);
        assertEquals(200, cliente.statusCode());
        assertEquals(2, json(cliente).get("direcciones").size());
        assertCodigo(404, "CLIENTE_NO_ENCONTRADO", post(token, "/api/v1/delivery/clientes/" + UuidV7.next() + "/direcciones",
                Map.of("direccion", "x")));
    }

    @Test
    void seBuscaPorTelefonoSoloDentroDelRestaurante() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        post(escenario.token(a, "cajero"), "/api/v1/delivery/clientes", clienteBody(UuidV7.next(), cedulaAleatoria()));

        assertEquals(1, json(get(escenario.token(a, "cajero"), "/api/v1/delivery/clientes?telefono=0991234567")).size());
        assertEquals(0, json(get(escenario.token(b, "cajero"), "/api/v1/delivery/clientes?telefono=0991234567")).size(),
                "otro restaurante no ve los clientes");
        assertCodigo(404, "CLIENTE_NO_ENCONTRADO", get(escenario.token(b, "cajero"),
                "/api/v1/delivery/clientes/" + UuidV7.next()));
    }

    // ------------------------------------------------------------ zonas y roles

    @Test
    void lasZonasSonLasActivasDeLaSucursalDelToken() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID otraSucursal = escenario.crearSucursal(r.tenantId(), "002");
        escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.50", "0");
        UUID inactiva = escenario.crearZona(r.tenantId(), r.sucursalId(), "Cerrada", "1", "0");
        escenario.ejecutar(r.tenantId(), "update zonas_delivery set activa = false where id = ?", inactiva);
        escenario.crearZona(r.tenantId(), otraSucursal, "Sur", "3", "0");

        Response zonas = get(escenario.token(r, "cajero"), "/api/v1/delivery/zonas");
        assertEquals(200, zonas.statusCode());
        assertEquals(1, json(zonas).size());
        assertEquals("Norte", json(zonas).get(0).get("nombre").asText());
    }

    @Test
    void soloCajaYAdministracionTomanPedidosADomicilio() {
        Restaurante r = escenario.crearRestaurante("10.00");
        assertEquals(403, get(escenario.token(r, "mesero"), "/api/v1/delivery/zonas").statusCode());
        assertEquals(403, get(escenario.token(r, "cocina"), "/api/v1/delivery/zonas").statusCode());
        given().when().get("/api/v1/delivery/zonas").then().statusCode(401);
        assertEquals(200, get(escenario.token(r, "admin"), "/api/v1/delivery/zonas").statusCode());
    }

    // ------------------------------------------------------------ pedido delivery

    @Test
    void elPedidoDeliverySumaElEnvioDeLaZonaYSeCreaIdempotente() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID[] cliente = clienteConDireccion(r);
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.50", "15.00");
        UUID pedidoId = UuidV7.next();

        Response creado = post(token, "/api/v1/delivery/pedidos", pedidoBody(pedidoId, cliente, zona, r, "2", "1.00"));

        assertEquals(201, creado.statusCode(), creado.asString());
        assertEquals(0, new BigDecimal("20.00").compareTo(json(creado).get("subtotal").decimalValue()));
        assertEquals(0, new BigDecimal("3.00").compareTo(json(creado).get("iva").decimalValue()));
        assertEquals(0, new BigDecimal("2.50").compareTo(json(creado).get("costoEnvio").decimalValue()));
        assertEquals(0, new BigDecimal("26.50").compareTo(json(creado).get("total").decimalValue()), "20 + 3 + 1 + 2.50");
        assertEquals("creado", json(creado).get("estado").asText());
        assertEquals("delivery_propio", escenario.texto(r.tenantId(), "select canal from pedidos where id = ?", pedidoId));
        assertEquals("contra_entrega", escenario.texto(r.tenantId(), "select momento_pago from pedidos where id = ?", pedidoId));
        assertEquals(cliente[1].toString(), escenario.texto(r.tenantId(), "select direccion_id::text from pedidos where id = ?", pedidoId));

        assertEquals(200, post(token, "/api/v1/delivery/pedidos", pedidoBody(pedidoId, cliente, zona, r, "2", "1.00")).statusCode(),
                "repetir la orden no crea otro pedido");
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos"));
    }

    @Test
    void elPedidoSePuedeCobrarAnticipadoYElMomentoQuedaGuardado() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID[] cliente = clienteConDireccion(r);
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.00", "0");
        UUID pedidoId = UuidV7.next();
        Map<String, Object> cuerpo = new java.util.HashMap<>(pedidoBody(pedidoId, cliente, zona, r, "1", "0"));
        cuerpo.put("momentoPago", "anticipado");

        assertEquals(201, post(escenario.token(r, "cajero"), "/api/v1/delivery/pedidos", cuerpo).statusCode());
        assertEquals("anticipado", escenario.texto(r.tenantId(), "select momento_pago from pedidos where id = ?", pedidoId));

        cuerpo.put("id", UuidV7.next().toString());
        cuerpo.put("momentoPago", "al_final");
        assertCodigo(422, "MOMENTO_PAGO_INVALIDO", post(escenario.token(r, "cajero"), "/api/v1/delivery/pedidos", cuerpo));
    }

    @Test
    void rechazaZonaDireccionOClienteQueNoCorresponden() {
        Restaurante r = escenario.crearRestaurante("10.00");
        Restaurante otro = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID[] cliente = clienteConDireccion(r);
        UUID[] ajeno = clienteConDireccion(r);
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.50", "0");
        UUID otraSucursal = escenario.crearSucursal(r.tenantId(), "002");
        UUID zonaDeOtraSucursal = escenario.crearZona(r.tenantId(), otraSucursal, "Sur", "3", "0");
        UUID inactiva = escenario.crearZona(r.tenantId(), r.sucursalId(), "Cerrada", "1", "0");
        escenario.ejecutar(r.tenantId(), "update zonas_delivery set activa = false where id = ?", inactiva);
        UUID zonaDeOtroRestaurante = escenario.crearZona(otro.tenantId(), otro.sucursalId(), "X", "1", "0");

        assertCodigo(404, "ZONA_NO_ENCONTRADA", post(token, "/api/v1/delivery/pedidos", pedidoBody(UuidV7.next(), cliente, zonaDeOtraSucursal, r, "1", "0")));
        assertCodigo(404, "ZONA_NO_ENCONTRADA", post(token, "/api/v1/delivery/pedidos", pedidoBody(UuidV7.next(), cliente, inactiva, r, "1", "0")));
        assertCodigo(404, "ZONA_NO_ENCONTRADA", post(token, "/api/v1/delivery/pedidos", pedidoBody(UuidV7.next(), cliente, zonaDeOtroRestaurante, r, "1", "0")));
        assertCodigo(422, "DIRECCION_NO_ES_DEL_CLIENTE",
                post(token, "/api/v1/delivery/pedidos", pedidoBody(UuidV7.next(), new UUID[] {cliente[0], ajeno[1]}, zona, r, "1", "0")));
        assertCodigo(404, "CLIENTE_NO_ENCONTRADO",
                post(token, "/api/v1/delivery/pedidos", pedidoBody(UuidV7.next(), new UUID[] {UuidV7.next(), cliente[1]}, zona, r, "1", "0")));
        assertCodigo(404, "DIRECCION_NO_ENCONTRADA",
                post(token, "/api/v1/delivery/pedidos", pedidoBody(UuidV7.next(), new UUID[] {cliente[0], UuidV7.next()}, zona, r, "1", "0")));
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from pedidos"), "no quedo ningun pedido a medias");
    }

    @Test
    void elPedidoMinimoDeLaZonaSeExigeSobreLosItems() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID[] cliente = clienteConDireccion(r);
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Lejos", "4.00", "30.00");

        assertCodigo(409, "PEDIDO_MINIMO_NO_ALCANZADO", post(token, "/api/v1/delivery/pedidos", pedidoBody(UuidV7.next(), cliente, zona, r, "2", "0")));
        assertEquals(201, post(token, "/api/v1/delivery/pedidos", pedidoBody(UuidV7.next(), cliente, zona, r, "3", "0")).statusCode(),
                "30.00 de items alcanza el minimo");
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from pedidos"));
    }

    @Test
    void elEndpointGeneralDePedidosSigueRechazandoElDelivery() {
        Restaurante r = escenario.crearRestaurante("10.00");
        Response res = post(escenario.token(r, "cajero"), "/api/v1/pedidos", Map.of("canal", "delivery_propio",
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", 1))));
        assertCodigo(422, "CANAL_NO_SOPORTADO", res);
    }

    @Test
    void anularUnItemRecalculaTotalesSinPerderElEnvio() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID otro = escenario.crearProducto(r.tenantId(), "Bebida", "5.00");
        String token = escenario.token(r, "cajero");
        UUID[] cliente = clienteConDireccion(r);
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.50", "0");
        UUID pedidoId = UuidV7.next();
        Response creado = post(token, "/api/v1/delivery/pedidos", Map.of("id", pedidoId.toString(),
                "clienteId", cliente[0].toString(), "direccionId", cliente[1].toString(), "zonaId", zona.toString(),
                "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", 1),
                        Map.of("productoId", otro.toString(), "cantidad", 1))));
        assertEquals(201, creado.statusCode(), creado.asString());   // 15 + 2.25 IVA + 2.50 = 19.75
        assertEquals(200, post(token, "/api/v1/pedidos/" + pedidoId + "/confirmar", "{}").statusCode());
        drenar();
        UUID item = UUID.fromString(escenario.texto(r.tenantId(),
                "select id::text from pedido_items where pedido_id = ? and nombre = 'Bebida'", pedidoId));

        Response anulado = post(token, "/api/v1/cocina/items/" + item + "/anular", Map.of("motivo", "se acabo"));
        assertEquals(200, anulado.statusCode(), anulado.asString());

        assertEquals("2.50", escenario.texto(r.tenantId(), "select costo_envio::text from pedidos where id = ?", pedidoId));
        assertEquals("14.00", escenario.texto(r.tenantId(), "select total::text from pedidos where id = ?", pedidoId),
                "10 + 1.50 IVA + 2.50 de envio");
    }

    // ---------------------------------------------------------------- factura

    @Test
    void laFacturaDeUnDeliveryIncluyeElEnvioYLaPropinaYCuadraConLaBase() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID[] cliente = clienteConDireccion(r);
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.50", "0");
        UUID pedidoId = UuidV7.next();
        post(token, "/api/v1/delivery/pedidos", pedidoBody(pedidoId, cliente, zona, r, "2", "1.00"));
        Response pago = post(token, "/api/v1/pagos", Map.of("id", UuidV7.next().toString(), "pedidoId", pedidoId.toString(),
                "metodo", "efectivo", "monto", new BigDecimal("26.50")));
        assertEquals(201, pago.statusCode(), pago.asString());

        drenar();

        String sql = "select %s from comprobantes where pedido_id = ? and tipo = '01'";
        assertEquals("22.50", escenario.texto(r.tenantId(), sql.formatted("subtotal_sin_impuestos::text"), pedidoId), "items 20 + envio 2.50");
        assertEquals("3.00", escenario.texto(r.tenantId(), sql.formatted("total_iva::text"), pedidoId));
        assertEquals("1.00", escenario.texto(r.tenantId(), sql.formatted("propina::text"), pedidoId));
        assertEquals("26.50", escenario.texto(r.tenantId(), sql.formatted("importe_total::text"), pedidoId));

        UUID comprobante = UUID.fromString(escenario.texto(r.tenantId(), sql.formatted("id::text"), pedidoId));
        assertEquals(2, escenario.contar(r.tenantId(), "select count(*) from comprobante_detalles where comprobante_id = ?", comprobante));
        assertEquals(0, new BigDecimal("22.50").compareTo(escenario.sumar(r.tenantId(),
                "select sum(base_imponible) from comprobante_detalles where comprobante_id = ?", comprobante)),
                "los detalles suman la base imponible del comprobante");
        assertEquals("ENVIO", escenario.texto(r.tenantId(),
                "select codigo from comprobante_detalles where comprobante_id = ? and linea = 2", comprobante));
        assertTrue(escenario.contar(r.tenantId(), "select count(*) from comprobante_detalles where comprobante_id = ? and codigo = 'ENVIO' and valor_iva = 0",
                comprobante) == 1);
    }

    @Test
    void laNotaDeCreditoDeUnDeliveryDevueltoAcreditaTambienElEnvioYLaPropina() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID[] cliente = clienteConDireccion(r);
        UUID zona = escenario.crearZona(r.tenantId(), r.sucursalId(), "Norte", "2.50", "0");
        UUID pedidoId = UuidV7.next();
        post(token, "/api/v1/delivery/pedidos", pedidoBody(pedidoId, cliente, zona, r, "2", "1.00"));
        UUID pagoId = UUID.fromString(json(post(token, "/api/v1/pagos", Map.of("id", UuidV7.next().toString(),
                "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("26.50")))).get("pagoId").asText());
        drenar();

        Response reembolso = post(escenario.token(r, "admin"), "/api/v1/pagos/" + pagoId + "/reembolsos",
                Map.of("id", UuidV7.next().toString(), "monto", new BigDecimal("26.50"), "motivo", "no llego"));
        assertEquals(201, reembolso.statusCode(), reembolso.asString());
        drenar();

        String sql = "select %s from comprobantes where pedido_id = ? and tipo = '04'";
        assertEquals("26.50", escenario.texto(r.tenantId(), sql.formatted("importe_total::text"), pedidoId));
        assertEquals("1.00", escenario.texto(r.tenantId(), sql.formatted("propina::text"), pedidoId));
        assertEquals("22.50", escenario.texto(r.tenantId(), sql.formatted("subtotal_sin_impuestos::text"), pedidoId));
        UUID nota = UUID.fromString(escenario.texto(r.tenantId(), sql.formatted("id::text"), pedidoId));
        assertEquals(2, escenario.contar(r.tenantId(), "select count(*) from comprobante_detalles where comprobante_id = ?", nota));
    }
}
