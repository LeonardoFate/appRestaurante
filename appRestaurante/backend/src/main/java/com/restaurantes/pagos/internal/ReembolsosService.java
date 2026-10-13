package com.restaurantes.pagos.internal;

import com.restaurantes.pagos.domain.PoliticaDeCobro;
import com.restaurantes.pagos.domain.PoliticaDeReembolso;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.shared.audit.Auditoria;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.events.Outbox;
import com.restaurantes.shared.ids.UuidV7;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reembolsos (devolucion de dinero de un pago). Se ejecuta dentro de la unidad de trabajo del tenant que abre el endpoint.
 *
 * <p>Todo en una sola transaccion: el reembolso, el estado del pago, el estado de cobro del pedido, su paso a
 * {@code devuelto_*} si ya se entrego, el evento {@code PagoReembolsado} y el registro de auditoria. Orden de bloqueo:
 * primero el pedido y despues el pago (el mismo que usa el cobro), para no provocar bloqueos cruzados.
 */
@ApplicationScoped
class ReembolsosService {

    @Inject
    PagoRepository repo;

    @Inject
    PedidosApi pedidos;

    @Inject
    Outbox outbox;

    @Inject
    Auditoria auditoria;

    ResultadoReembolso reembolsar(UUID reembolsoId, UUID pagoId, BigDecimal montoSolicitado, String motivo, UUID usuarioId) {
        if (motivo == null || motivo.isBlank()) {
            throw ReglaDeNegocioException.invalido("MOTIVO_REQUERIDO", "Indique el motivo del reembolso");
        }
        if (montoSolicitado == null || montoSolicitado.signum() <= 0) {
            throw ReglaDeNegocioException.invalido("MONTO_INVALIDO", "El monto debe ser mayor que cero");
        }
        BigDecimal monto = montoSolicitado.setScale(2, RoundingMode.HALF_UP);
        UUID id = reembolsoId != null ? reembolsoId : UuidV7.next();

        repo.bloquearReembolso(id);
        Optional<PagoRepository.ReembolsoGuardado> existente = repo.reembolso(id);
        if (existente.isPresent()) {
            return repeticion(existente.get(), pagoId, monto);
        }

        // Primero el pedido, despues el pago.
        UUID pedidoId = repo.pago(pagoId, false)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PAGO_NO_ENCONTRADO", "El pago no existe"))
                .pedidoId();
        PedidoVista pedido = pedidos.buscarBloqueando(pedidoId)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PAGO_NO_ENCONTRADO", "El pago no existe"));
        PagoRepository.PagoFila pago = repo.pago(pagoId, true)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PAGO_NO_ENCONTRADO", "El pago no existe"));

        if (!pago.metodo().soportado()) {
            throw ReglaDeNegocioException.invalido("METODO_NO_SOPORTADO",
                    "Los pagos por '" + pago.metodo().valor() + "' aun no se pueden reembolsar desde aqui");
        }
        PoliticaDeReembolso.validarEstadoDelPago(pago.estado());
        BigDecimal yaReembolsado = repo.reembolsado(pagoId);
        BigDecimal reembolsable = PoliticaDeReembolso.reembolsable(pago.monto(), yaReembolsado);
        PoliticaDeReembolso.validar(monto, reembolsable, motivo);

        repo.insertarReembolso(id, pagoId, monto, motivo.trim(), usuarioId);
        repo.actualizarEstadoDelPago(pagoId, PoliticaDeReembolso.estadoDelPagoTras(reembolsable, monto));

        // Lo cobrado NETO decide el estado de cobro del pedido: nada cobrado = reembolsado; algo cobrado = parcial.
        BigDecimal neto = repo.sumaCobrada(pedidoId);
        EstadoPago estadoPagoPedido = PoliticaDeCobro.estadoTras(pedido.totales().total(), neto, true);
        pedidos.actualizarEstadoPago(pedidoId, estadoPagoPedido);
        pedidos.registrarDevolucion(pedidoId, neto.signum() <= 0, motivo, usuarioId);

        Map<String, Object> evento = new HashMap<>();
        evento.put("reembolsoId", id.toString());
        evento.put("pagoId", pagoId.toString());
        evento.put("pedidoId", pedidoId.toString());
        evento.put("monto", monto.toPlainString());
        evento.put("motivo", motivo.trim());
        evento.put("totalDelPedido", neto.signum() <= 0);
        outbox.emitir("PagoReembolsado", "pago", pagoId, evento);

        auditoria.registrar(usuarioId, "pago.reembolsar", "pago", pagoId,
                Map.of("estadoPagoDelPedido", pedido.estadoPago().valor(), "reembolsable", reembolsable.toPlainString()),
                Map.of("reembolsoId", id.toString(), "monto", monto.toPlainString(), "motivo", motivo.trim(),
                        "estadoPagoDelPedido", estadoPagoPedido.valor(), "cobradoNeto", neto.toPlainString()));

        String estadoPedido = pedidos.buscar(pedidoId).orElseThrow().estado();
        return new ResultadoReembolso(id, pagoId, pedidoId, monto, estadoPagoPedido, estadoPedido, neto, true);
    }

    /** Repetir el mismo reembolso devuelve el resultado original; reutilizar el id con otros datos es un conflicto. */
    private ResultadoReembolso repeticion(PagoRepository.ReembolsoGuardado guardado, UUID pagoId, BigDecimal monto) {
        if (!guardado.pagoId().equals(pagoId) || guardado.monto().compareTo(monto) != 0) {
            throw ReglaDeNegocioException.conflicto("REEMBOLSO_ID_REUTILIZADO", "El id del reembolso ya se uso con otros datos");
        }
        UUID pedidoId = repo.pago(pagoId, false).orElseThrow().pedidoId();
        PedidoVista pedido = pedidos.buscar(pedidoId).orElseThrow();
        return new ResultadoReembolso(guardado.id(), pagoId, pedidoId, guardado.monto(), pedido.estadoPago(), pedido.estado(),
                repo.sumaCobrada(pedidoId), false);
    }
}
