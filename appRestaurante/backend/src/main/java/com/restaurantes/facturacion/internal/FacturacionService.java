package com.restaurantes.facturacion.internal;

import com.restaurantes.facturacion.domain.Receptor;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.plataforma.api.DatosFiscales;
import com.restaurantes.plataforma.api.EmisorFiscal;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.jobs.ColaDeJobs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Genera la factura de un pedido pagado y la nota de credito de una devolucion total, y encola su emision. Se ejecuta
 * dentro de la unidad de trabajo del tenant (la abre el relay del outbox): el comprobante, su secuencial, sus detalles y
 * el trabajo {@code sri.emitir} se confirman juntos o no queda nada (el contador de secuenciales tampoco avanza).
 *
 * <p>Esta entrega deja los comprobantes en estado {@code generado}. Generar el XML, firmarlo y enviarlo al SRI lo hace el
 * procesador del trabajo {@code sri.emitir}, que se implementara al final (necesita el certificado de pruebas). Ese
 * procesador debera emitir la nota de credito DESPUES de que su factura este autorizada.
 */
@ApplicationScoped
class FacturacionService {

    private static final Logger LOG = Logger.getLogger(FacturacionService.class);

    static final String FACTURA = "01";
    static final String NOTA_DE_CREDITO = "04";

    @Inject
    ComprobanteRepository repo;

    @Inject
    PedidosApi pedidos;

    @Inject
    DatosFiscales datosFiscales;

    @Inject
    ColaDeJobs cola;

    @ConfigProperty(name = "app.zona-horaria", defaultValue = "America/Guayaquil")
    String zonaHoraria;

    /**
     * @return el id del comprobante creado, o vacio si el pedido ya tenia factura (reentrega del evento)
     */
    Optional<UUID> generarFactura(UUID pedidoId) {
        PedidoVista pedido = pedidos.buscar(pedidoId)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido " + pedidoId + " no existe"));
        // El evento PedidoPagado dice que el pedido se pago por completo en su momento: aunque despues se haya devuelto
        // parte o todo, la venta ocurrio y se factura (y la nota de credito acredita la devolucion). Solo se rechaza un
        // pedido que nunca llego a pagarse del todo.
        if (pedido.estadoPago() != EstadoPago.PAGADO && pedido.estadoPago() != EstadoPago.REEMBOLSADO_PARCIAL
                && pedido.estadoPago() != EstadoPago.REEMBOLSADO) {
            throw ReglaDeNegocioException.conflicto("PEDIDO_NO_PAGADO", "El pedido " + pedidoId + " no esta pagado: no se factura");
        }
        if (repo.existeFacturaVigente(pedidoId)) {
            LOG.debugf("El pedido %s ya tiene factura: se ignora la reentrega", pedidoId);
            return Optional.empty();
        }

        EmisorFiscal emisor = datosFiscales.emisorDe(pedido.sucursalId());
        LocalDate fechaEmision = LocalDate.now(ZoneId.of(zonaHoraria));
        String secuencial = repo.siguienteSecuencial(pedido.sucursalId(), FACTURA, emisor.ambiente());
        String claveAcceso = repo.generarClaveAcceso(fechaEmision, FACTURA, emisor.ruc(), emisor.ambiente(),
                emisor.codEstablecimiento(), emisor.codPuntoEmision(), secuencial);

        UUID comprobanteId = UuidV7.next();
        repo.insertarFactura(comprobanteId, pedido, emisor.ambiente(), secuencial, claveAcceso, fechaEmision,
                Receptor.consumidorFinal());
        repo.insertarDetalles(comprobanteId, pedido.lineas(), pedido.totales().costoEnvio());
        repo.registrarEvento(comprobanteId, "generado", null);

        // El trabajo existe si y solo si el comprobante se confirmo. La clave unica evita duplicarlo.
        cola.encolar("sri", "sri.emitir", Map.of("comprobanteId", comprobanteId.toString(), "tipo", FACTURA), "sri:" + comprobanteId);

        // Si la venta ya se devolvio por completo antes de que llegara este evento, tambien hay que acreditarla.
        reconciliarNotaDeCredito(pedidoId, null);
        return Optional.of(comprobanteId);
    }

    /**
     * Genera la nota de credito de un pedido cuya venta se devolvio POR COMPLETO y que tiene factura. Es independiente del
     * orden en que lleguen los eventos: se llama tanto al facturar como al reembolsar, y solo actua cuando se cumplen las
     * dos condiciones y aun no existe la nota.
     *
     * <p>Las devoluciones PARCIALES no generan nota de credito todavia: repartir un monto entre base imponible e IVA exige
     * criterios del SRI sin verificar (ver el backlog y los riesgos).
     *
     * @param motivo motivo de la devolucion; si es null se usa uno generico
     * @return el id de la nota de credito creada, o vacio si no corresponde crearla
     */
    Optional<UUID> reconciliarNotaDeCredito(UUID pedidoId, String motivo) {
        PedidoVista pedido = pedidos.buscar(pedidoId).orElse(null);
        if (pedido == null || pedido.estadoPago() != EstadoPago.REEMBOLSADO) {
            return Optional.empty();
        }
        Optional<ComprobanteRepository.FacturaVigente> factura = repo.facturaVigente(pedidoId);
        if (factura.isEmpty() || repo.existeNotaDeCredito(factura.get().id())) {
            return Optional.empty();
        }

        EmisorFiscal emisor = datosFiscales.emisorDe(factura.get().sucursalId());
        LocalDate fechaEmision = LocalDate.now(ZoneId.of(zonaHoraria));
        String secuencial = repo.siguienteSecuencial(factura.get().sucursalId(), NOTA_DE_CREDITO, factura.get().ambiente());
        String claveAcceso = repo.generarClaveAcceso(fechaEmision, NOTA_DE_CREDITO, emisor.ruc(), factura.get().ambiente(),
                emisor.codEstablecimiento(), emisor.codPuntoEmision(), secuencial);

        UUID notaId = UuidV7.next();
        String motivoNota = motivo == null || motivo.isBlank() ? "Devolucion total de la venta" : motivo;
        repo.insertarNotaDeCredito(notaId, pedidoId, factura.get(), secuencial, claveAcceso, fechaEmision, motivoNota);
        repo.copiarDetalles(factura.get().id(), notaId);
        repo.registrarEvento(notaId, "generado", null);
        cola.encolar("sri", "sri.emitir", Map.of("comprobanteId", notaId.toString(), "tipo", NOTA_DE_CREDITO), "sri:" + notaId);
        return Optional.of(notaId);
    }
}
