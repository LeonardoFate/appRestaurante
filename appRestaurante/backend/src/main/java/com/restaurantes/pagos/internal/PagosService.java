package com.restaurantes.pagos.internal;

import com.restaurantes.pagos.domain.MetodoPago;
import com.restaurantes.pagos.domain.PoliticaDeCobro;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.EstadoPago;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.events.Outbox;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.security.CallerContext;
import com.restaurantes.shared.tenancy.TenantTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
class PagosService {

    @Inject
    TenantTransaction tx;

    @Inject
    PagoRepository repo;

    @Inject
    PedidosApi pedidos;

    @Inject
    Outbox outbox;

    @Inject
    CallerContext caller;

    /**
     * Registra un cobro. Idempotente por {@code pagoId}. Todo ocurre en una transaccion: el pago, el cambio
     * de estado de cobro del pedido (que hace el modulo pedidos) y el evento {@code PedidoPagado} del outbox.
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

        return tx.withTenant(caller.tenantId(), c -> {
            repo.bloquear(id);

            Optional<PagoRepository.PagoGuardado> existente = repo.buscar(id);
            if (existente.isPresent()) {
                return repeticion(existente.get(), pedidoId, monto);
            }

            // Bloquea el pedido ANTES de leer lo cobrado: dos cobros simultaneos del mismo pedido se serializan y el
            // segundo ve lo que confirmo el primero. Sin esto el saldo y el estado final se calcularian con datos viejos.
            PedidoVista pedido = pedidos.buscarBloqueando(pedidoId)
                    .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe"));

            BigDecimal cobrado = repo.sumaCobrada(pedidoId);
            BigDecimal saldo = PoliticaDeCobro.saldo(pedido.totales().total(), cobrado);
            PoliticaDeCobro.validarMonto(monto, saldo);

            repo.insertar(id, pedidoId, metodo, monto);

            BigDecimal cobradoTotal = cobrado.add(monto);
            EstadoPago nuevoEstado = PoliticaDeCobro.estadoTras(pedido.totales().total(), cobradoTotal);
            pedidos.actualizarEstadoPago(pedidoId, nuevoEstado);

            if (nuevoEstado == EstadoPago.PAGADO) {
                outbox.emitir("PedidoPagado", "pedido", pedidoId, Map.of(
                        "pedidoId", pedidoId.toString(),
                        "total", pedido.totales().total().toPlainString(),
                        "ultimoPagoId", id.toString()));
            }
            return new ResultadoPago(id, pedidoId, monto, nuevoEstado,
                    PoliticaDeCobro.saldo(pedido.totales().total(), cobradoTotal), true);
        });
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
