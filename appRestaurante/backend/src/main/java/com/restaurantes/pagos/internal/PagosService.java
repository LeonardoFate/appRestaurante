package com.restaurantes.pagos.internal;

import com.restaurantes.pagos.api.PagosApi;
import com.restaurantes.pagos.domain.MetodoPago;
import com.restaurantes.pagos.domain.PoliticaDeCobro;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.events.Outbox;
import com.restaurantes.shared.ids.UuidV7;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos de uso de pagos. Se ejecutan dentro de la unidad de trabajo del tenant que abre el endpoint REST.
 */
@ApplicationScoped
class PagosService implements PagosApi {

    @Inject
    PagoRepository repo;

    @Inject
    PedidosApi pedidos;

    @Inject
    Outbox outbox;

    /**
     * Registra un cobro. Idempotente por {@code pagoId}. Todo ocurre en la transaccion del llamante: el pago, el
     * cambio de estado de cobro del pedido (que hace el modulo pedidos) y el evento {@code PedidoPagado} del outbox.
     */
    ResultadoPago registrar(UUID pagoId, UUID pedidoId, MetodoPago metodo, BigDecimal montoSolicitado) {
        if (!metodo.soportado()) {
            throw ReglaDeNegocioException.invalido("METODO_NO_SOPORTADO", "El metodo de pago '" + metodo.valor() + "' aun no esta disponible");
        }
        if (montoSolicitado == null || montoSolicitado.signum() <= 0) {
            throw ReglaDeNegocioException.invalido("MONTO_INVALIDO", "El monto debe ser mayor que cero");
        }
        BigDecimal monto = montoSolicitado.setScale(2, RoundingMode.HALF_UP);
        UUID id = pagoId != null ? pagoId : UuidV7.next();

        repo.bloquear(id);

        Optional<PagoRepository.PagoGuardado> existente = repo.buscar(id);
        if (existente.isPresent()) {
            return repeticion(existente.get(), pedidoId, monto);
        }

        // Bloquea el pedido ANTES de leer lo cobrado: dos cobros simultaneos del mismo pedido se serializan y el
        // segundo ve lo que confirmo el primero. Sin esto el saldo y el estado final se calcularian con datos viejos.
        PedidoVista pedido = pedidos.buscarBloqueando(pedidoId)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe"));
        if (pedido.estadoPago() == EstadoPago.REEMBOLSADO) {
            // Se devolvio todo: cobrar de nuevo el mismo pedido dejaria la facturacion incoherente (la nota de credito
            // ya anula la factura). Hay que tomar un pedido nuevo.
            throw ReglaDeNegocioException.conflicto("PEDIDO_REEMBOLSADO",
                    "El pedido ya fue reembolsado por completo: cree un pedido nuevo para volver a cobrar");
        }

        BigDecimal cobrado = repo.sumaCobrada(pedidoId);
        BigDecimal saldo = PoliticaDeCobro.saldo(pedido.totales().total(), cobrado);
        PoliticaDeCobro.validarMonto(monto, saldo);

        repo.insertar(id, pedidoId, metodo, monto);

        BigDecimal cobradoTotal = cobrado.add(monto);
        EstadoPago nuevoEstado = PoliticaDeCobro.estadoTras(pedido.totales().total(), cobradoTotal, repo.hayReembolsos(pedidoId));
        pedidos.actualizarEstadoPago(pedidoId, nuevoEstado);

        if (nuevoEstado == EstadoPago.PAGADO) {
            outbox.emitir("PedidoPagado", "pedido", pedidoId, Map.of(
                    "pedidoId", pedidoId.toString(),
                    "total", pedido.totales().total().toPlainString(),
                    "ultimoPagoId", id.toString()));
        }
        return new ResultadoPago(id, pedidoId, monto, nuevoEstado,
                PoliticaDeCobro.saldo(pedido.totales().total(), cobradoTotal), true);
    }

    @Override
    public BigDecimal cobrarSaldoEnEfectivo(UUID pedidoId) {
        PedidoVista pedido = pedidos.buscarBloqueando(pedidoId)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe"));
        BigDecimal saldo = PoliticaDeCobro.saldo(pedido.totales().total(), repo.sumaCobrada(pedidoId));
        if (saldo.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        registrar(UuidV7.next(), pedidoId, MetodoPago.EFECTIVO, saldo);
        return saldo;
    }

    /** Repetir el mismo pago devuelve el resultado original; reutilizar el id con otros datos es un conflicto. */
    private ResultadoPago repeticion(PagoRepository.PagoGuardado guardado, UUID pedidoId, BigDecimal monto) {
        if (!guardado.pedidoId().equals(pedidoId) || guardado.monto().compareTo(monto) != 0) {
            throw ReglaDeNegocioException.conflicto("PAGO_ID_REUTILIZADO",
                    "El id del pago ya se uso con otros datos");
        }
        PedidoVista pedido = pedidos.buscar(pedidoId).orElseThrow();
        BigDecimal saldo = PoliticaDeCobro.saldo(pedido.totales().total(), repo.sumaCobrada(pedidoId));
        return new ResultadoPago(guardado.id(), pedidoId, guardado.monto(), pedido.estadoPago(), saldo, false);
    }
}
