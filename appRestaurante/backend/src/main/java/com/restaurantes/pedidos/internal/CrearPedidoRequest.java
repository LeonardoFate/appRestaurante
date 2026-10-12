package com.restaurantes.pedidos.internal;

import com.restaurantes.pedidos.api.NuevoPedido;
import com.restaurantes.pedidos.domain.CanalPedido;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Cuerpo de {@code POST /api/v1/pedidos}. El cliente NO envia precios ni totales: salen del menu. */
public record CrearPedidoRequest(
        UUID id,
        @NotNull String canal,
        UUID mesaId,
        @PositiveOrZero BigDecimal propina,
        @NotEmpty List<@Valid Item> items) {

    public record Item(
            UUID id,
            @NotNull UUID productoId,
            @NotNull @jakarta.validation.constraints.Positive BigDecimal cantidad,
            @Size(max = 200) String nota) {
    }

    NuevoPedido aOrden() {
        CanalPedido canalPedido;
        try {
            canalPedido = CanalPedido.desde(canal);
        } catch (IllegalArgumentException e) {
            throw ReglaDeNegocioException.invalido("CANAL_INVALIDO", "Canal desconocido: " + canal);
        }
        return new NuevoPedido(id, canalPedido, mesaId, propina,
                items.stream().map(i -> new NuevoPedido.NuevaLinea(i.id(), i.productoId(), i.cantidad(), i.nota())).toList());
    }
}
