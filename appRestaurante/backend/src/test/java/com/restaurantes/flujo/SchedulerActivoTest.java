package com.restaurantes.flujo;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.restaurantes.flujo.Escenario.Restaurante;
import com.restaurantes.shared.ids.UuidV7;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Con el planificador encendido nadie invoca el relay a mano: la factura debe aparecer sola. */
@QuarkusTest
@TestProfile(SchedulerActivo.class)
class SchedulerActivoTest {

    @Inject
    Escenario escenario;

    @Test
    void elPlanificadorGeneraLaFacturaSinIntervencionManual() throws InterruptedException {
        Restaurante r = escenario.crearRestaurante("10.00");
        String token = escenario.token(r, "cajero");
        UUID pedidoId = UuidV7.next();
        assertEquals(201, given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("id", pedidoId.toString(), "canal", "salon", "mesaId", r.mesaId().toString(),
                        "items", List.of(Map.of("productoId", r.productoId().toString(), "cantidad", new BigDecimal("1")))))
                .when().post("/api/v1/pedidos").statusCode());
        assertEquals(201, given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("id", UuidV7.next().toString(), "pedidoId", pedidoId.toString(), "metodo", "efectivo", "monto", new BigDecimal("11.50")))
                .when().post("/api/v1/pagos").statusCode());

        long limite = System.currentTimeMillis() + 30_000;
        long comprobantes = 0;
        while (System.currentTimeMillis() < limite && comprobantes == 0) {
            Thread.sleep(300);
            comprobantes = escenario.contar(r.tenantId(), "select count(*) from comprobantes where pedido_id = ?", pedidoId);
        }

        assertEquals(1, comprobantes, "el relay periodico debio generar la factura en menos de 30 s");
        assertTrue(escenario.contar(r.tenantId(), "select count(*) from jobs where tipo = 'sri.emitir'") >= 1);
        assertEquals(1, escenario.contar(r.tenantId(),
                "select count(*) from outbox where tipo = 'PedidoPagado' and agregado_id = ? and publicado_en is not null", pedidoId));
    }
}
