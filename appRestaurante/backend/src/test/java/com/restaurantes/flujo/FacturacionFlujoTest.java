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
import java.time.LocalDate;
import java.time.ZoneId;
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
 * De extremo a extremo: cobrar un pedido por HTTP -> evento PedidoPagado en el outbox -> relay -> factura generada y
 * trabajo sri.emitir encolado. Contra la base real, sin mocks.
 */
@QuarkusTest
class FacturacionFlujoTest {

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
            throw new IllegalStateException(e);
        }
    }

    /** Crea un pedido de salon con {@code cantidad} unidades y lo paga completo. Devuelve su id. */
    private UUID pedidoPagado(Restaurante r, String cantidad) {
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        Response pedido = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("id", pedidoId.toString(), "canal", "salon", "mesaId", r.mesaId().toString(),
                        "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal(cantidad)))))
                .when().post("/api/v1/pedidos");
        assertEquals(201, pedido.statusCode(), pedido.asString());
        BigDecimal total = json(pedido).get("total").decimalValue();
        Response pago = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("id", UuidV7.next().toString(), "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", total))
                .when().post("/api/v1/pagos");
        assertEquals(201, pago.statusCode(), pago.asString());
        assertEquals("pagado", json(pago).get("estadoPago").asText());
        return pedidoId;
    }

    private void drenar() {
        for (int i = 0; i < 50 && relay.publicarLote() > 0; i++) {
            // sigue
        }
    }

    private String comprobante(Restaurante r, UUID pedidoId, String columna) {
        return escenario.texto(r.tenantId(), "select " + columna + " from comprobantes where pedido_id = ? and tipo = '01'", pedidoId);
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

    // ------------------------------------------------------------------ pruebas

    @Test
    void unPedidoPagadoGeneraSuFacturaYEncolaSuEmision() {
        Restaurante r = escenario.crearRestaurante("3.33");
        UUID pedidoId = pedidoPagado(r, "3");   // base 9.99, IVA 1.50 (redondeo por linea), total 11.49

        drenar();

        assertEquals("1", comprobante(r, pedidoId, "count(*)"));
        assertEquals("generado", comprobante(r, pedidoId, "estado_sri"));
        assertEquals("pruebas", comprobante(r, pedidoId, "ambiente"));
        assertEquals("000000001", comprobante(r, pedidoId, "secuencial"));
        assertEquals(LocalDate.now(ZoneId.of("America/Guayaquil")).toString(), comprobante(r, pedidoId, "fecha_emision::text"));
        assertEquals("07", comprobante(r, pedidoId, "receptor_tipo_identificacion"));
        assertEquals("9999999999999", comprobante(r, pedidoId, "receptor_identificacion"));
        assertEquals("CONSUMIDOR FINAL", comprobante(r, pedidoId, "receptor_razon_social"));
        assertEquals("9.99", comprobante(r, pedidoId, "subtotal_sin_impuestos::text"));
        assertEquals("1.50", comprobante(r, pedidoId, "total_iva::text"));
        assertEquals("11.49", comprobante(r, pedidoId, "importe_total::text"));

        // Los detalles reproducen exactamente los totales del comprobante (misma fuente de redondeo).
        UUID comprobanteId = UUID.fromString(comprobante(r, pedidoId, "id::text"));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from comprobante_detalles where comprobante_id = ?", comprobanteId));
        assertEquals(0, new BigDecimal("9.99").compareTo(escenario.sumar(r.tenantId(),
                "select sum(base_imponible) from comprobante_detalles where comprobante_id = ?", comprobanteId)));
        assertEquals(0, new BigDecimal("1.50").compareTo(escenario.sumar(r.tenantId(),
                "select sum(valor_iva) from comprobante_detalles where comprobante_id = ?", comprobanteId)));

        // Historial del comprobante y trabajo de emision encolado (aun sin procesador: lo implementa el spike del SRI).
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from comprobante_eventos where comprobante_id = ? and estado = 'generado'", comprobanteId));
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from jobs where tipo = 'sri.emitir' and estado = 'pendiente' and payload->>'comprobanteId' = ?",
                comprobanteId.toString()));
        // El evento quedo publicado.
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from outbox where tipo = 'PedidoPagado' and agregado_id = ? and publicado_en is not null", pedidoId));
    }

    @Test
    void laFacturaDeUnPedidoConPropinaSeparaLaPropinaYCuadra() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        Response pedido = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("id", pedidoId.toString(), "canal", "salon", "mesaId", r.mesaId().toString(), "propina", new BigDecimal("1.15"),
                        "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("1")))))
                .when().post("/api/v1/pedidos");
        assertEquals(201, pedido.statusCode(), pedido.asString());   // 10 + 1.50 IVA + 1.15 propina = 12.65
        Response pago = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("id", UuidV7.next().toString(), "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("12.65")))
                .when().post("/api/v1/pagos");
        assertEquals(201, pago.statusCode(), pago.asString());

        drenar();

        assertEquals("10.00", comprobante(r, pedidoId, "subtotal_sin_impuestos::text"));
        assertEquals("1.50", comprobante(r, pedidoId, "total_iva::text"));
        assertEquals("1.15", comprobante(r, pedidoId, "propina::text"));
        assertEquals("12.65", comprobante(r, pedidoId, "importe_total::text"));
    }

    @Test
    void laClaveDeAccesoTiene49DigitosYLaEstructuraDelSri() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoPagado(r, "1");
        drenar();

        String clave = comprobante(r, pedidoId, "clave_acceso");
        String ruc = escenario.texto(r.tenantId(), "select ruc from tenants where id = ?", r.tenantId());
        String fecha = comprobante(r, pedidoId, "to_char(fecha_emision, 'DDMMYYYY')");

        assertEquals(49, clave.length());
        assertTrue(clave.matches("[0-9]{49}"));
        assertEquals(fecha, clave.substring(0, 8), "fecha de emision ddmmaaaa");
        assertEquals("01", clave.substring(8, 10), "tipo de comprobante: factura");
        assertEquals(ruc, clave.substring(10, 23), "RUC del emisor");
        assertEquals("1", clave.substring(23, 24), "ambiente de pruebas");
        assertEquals("001001", clave.substring(24, 30), "establecimiento y punto de emision");
        assertEquals("000000001", clave.substring(30, 39), "secuencial");
        // Misma funcion de la base con los mismos datos: el digito verificador coincide.
        assertEquals("true", escenario.texto(r.tenantId(), """
                select (c.clave_acceso = app.generar_clave_acceso(c.fecha_emision, '01', t.ruc, c.ambiente, '001', '001', c.secuencial))::text
                  from comprobantes c join tenants t on t.id = c.tenant_id where c.pedido_id = ?
                """, pedidoId));
    }

    @Test
    void losSecuencialesSonConsecutivosPorRestauranteEIndependientes() {
        Restaurante a = escenario.crearRestaurante("10.00");
        Restaurante b = escenario.crearRestaurante("10.00");
        UUID a1 = pedidoPagado(a, "1");
        UUID a2 = pedidoPagado(a, "1");
        UUID b1 = pedidoPagado(b, "1");

        drenar();

        assertEquals("000000001", comprobante(a, a1, "secuencial"));
        assertEquals("000000002", comprobante(a, a2, "secuencial"));
        assertEquals("000000001", comprobante(b, b1, "secuencial"), "cada restaurante lleva su propia numeracion");
        assertEquals(0, escenario.contar(b.tenantId(), "select count(*) from comprobantes where pedido_id = ?", a1),
                "un restaurante no ve las facturas de otro");
    }

    @Test
    void unPedidoSoloPagadoEnParteNoSeFactura() {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("id", pedidoId.toString(), "canal", "salon", "mesaId", r.mesaId().toString(),
                        "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("2")))))
                .when().post("/api/v1/pedidos");
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("id", UuidV7.next().toString(), "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("5.00")))
                .when().post("/api/v1/pagos");

        drenar();

        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from comprobantes"));
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from jobs"));
    }

    @Test
    void laReentregaDelEventoNoDuplicaLaFacturaNiElTrabajo() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoPagado(r, "1");
        drenar();
        assertEquals("1", comprobante(r, pedidoId, "count(*)"));

        // El relay murio antes de confirmar la marca de publicado: el evento vuelve a quedar pendiente.
        admin.ejecutar("update outbox set publicado_en = null where agregado_id = ? and tipo = 'PedidoPagado'", pedidoId);
        drenar();

        assertEquals("1", comprobante(r, pedidoId, "count(*)"), "no se emite una segunda factura");
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from jobs where tipo = 'sri.emitir'"), "ni un segundo trabajo");
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from eventos_procesados where consumidor = 'facturacion.generar-factura'"));
        assertEquals("000000001", comprobante(r, pedidoId, "secuencial"), "y el secuencial no avanzo");
    }

    @Test
    void siFallaLaFacturacionNoQuedaNadaAMediasYElReintentoLaCompleta() {
        Restaurante r = escenario.crearRestaurante("10.00");
        UUID pedidoId = pedidoPagado(r, "1");
        // El contador de secuenciales esta al limite: el siguiente numero violaria su restriccion y la facturacion falla.
        escenario.ejecutar(r.tenantId(), "insert into secuenciales(tenant_id, sucursal_id, tipo_comprobante, ambiente, ultimo_valor) "
                + "values (?, ?, '01', 'pruebas', 999999999)", r.tenantId(), r.sucursalId());

        drenar();

        assertEquals("0", comprobante(r, pedidoId, "count(*)"), "no quedo ningun comprobante a medias");
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from comprobante_detalles"));
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from jobs"), "ni trabajo huerfano");
        assertEquals(0, escenario.contar(r.tenantId(), "select count(*) from eventos_procesados where consumidor = 'facturacion.generar-factura'"));
        assertEquals(999999999L, escenario.contar(r.tenantId(), "select ultimo_valor from secuenciales where tipo_comprobante = '01'"),
                "el contador no se consumio");
        assertEquals(1, escenario.contar(r.tenantId(), "select intentos from outbox where tipo = 'PedidoPagado' and agregado_id = ?", pedidoId));

        // Se corrige la causa y vence la espera: el reintento termina el trabajo, exactamente una vez.
        escenario.ejecutar(r.tenantId(), "update secuenciales set ultimo_valor = 5 where tipo_comprobante = '01'");
        admin.ejecutar("update outbox set proximo_intento_en = now() where agregado_id = ? and tipo = 'PedidoPagado'", pedidoId);
        drenar();

        assertEquals("1", comprobante(r, pedidoId, "count(*)"));
        assertEquals("000000006", comprobante(r, pedidoId, "secuencial"));
        assertEquals(1, escenario.contar(r.tenantId(), "select count(*) from jobs where tipo = 'sri.emitir'"));
    }

    @Test
    void relaysSimultaneosGeneranUnaFacturaPorPedidoConSecuencialesConsecutivos() throws Exception {
        Restaurante r = escenario.crearRestaurante("10.00");
        List<UUID> pedidos = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pedidos.add(pedidoPagado(r, "1"));
        }

        simultaneamente(5, () -> {
            int total = 0;
            int n;
            while ((n = relay.publicarLote()) > 0) {
                total += n;
            }
            return total;
        });

        assertEquals(6, escenario.contar(r.tenantId(), "select count(*) from comprobantes"));
        assertEquals(6, escenario.contar(r.tenantId(), "select count(distinct pedido_id) from comprobantes"), "una factura por pedido");
        assertEquals("000000001,000000002,000000003,000000004,000000005,000000006",
                escenario.texto(r.tenantId(), "select string_agg(secuencial, ',' order by secuencial) from comprobantes"),
                "numeracion consecutiva, sin huecos ni duplicados");
        assertEquals(6, escenario.contar(r.tenantId(), "select count(*) from jobs where tipo = 'sri.emitir'"));
    }
}
