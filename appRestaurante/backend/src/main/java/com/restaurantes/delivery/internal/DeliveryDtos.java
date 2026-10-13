package com.restaurantes.delivery.internal;

import com.restaurantes.pedidos.api.NuevoPedido;
import com.restaurantes.pedidos.api.PedidoVista;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Contratos HTTP del modulo delivery. El cliente NO envia precios, costo de envio ni totales. */
final class DeliveryDtos {

    private DeliveryDtos() {
    }

    record CrearClienteRequest(UUID id,
                               @NotBlank @Size(max = 120) String nombre,
                               @NotBlank String tipoIdentificacion,
                               @NotBlank @Size(max = 20) String identificacion,
                               @NotBlank @Size(max = 30) String telefono,
                               @Size(max = 120) String email) {
    }

    record CrearDireccionRequest(UUID id,
                                 @Size(max = 40) String etiqueta,
                                 @NotBlank @Size(max = 200) String direccion,
                                 @Size(max = 200) String referencia) {
    }

    record DireccionResponse(UUID id, String etiqueta, String direccion, String referencia) {
        static DireccionResponse de(ClienteRepository.DireccionFila d) {
            return new DireccionResponse(d.id(), d.etiqueta(), d.direccion(), d.referencia());
        }
    }

    record ClienteResponse(UUID id, String nombre, String tipoIdentificacion, String identificacion, String telefono,
                           String email, List<DireccionResponse> direcciones) {
        static ClienteResponse de(ClienteRepository.ClienteFila c, List<ClienteRepository.DireccionFila> direcciones) {
            return new ClienteResponse(c.id(), c.nombre(), c.tipoIdentificacion(), c.identificacion(), c.telefono(), c.email(),
                    direcciones.stream().map(DireccionResponse::de).toList());
        }
    }

    record ZonaResponse(UUID id, String nombre, BigDecimal costoEnvio, BigDecimal pedidoMinimo, Integer tiempoEstimadoMin) {
        static ZonaResponse de(ZonaRepository.ZonaFila z) {
            return new ZonaResponse(z.id(), z.nombre(), z.costoEnvio(), z.pedidoMinimo(), z.tiempoEstimadoMin());
        }
    }

    record CrearPedidoDeliveryRequest(UUID id,
                                      @NotNull UUID clienteId,
                                      @NotNull UUID direccionId,
                                      @NotNull UUID zonaId,
                                      String momentoPago,
                                      @PositiveOrZero BigDecimal propina,
                                      @NotEmpty List<@Valid Item> items) {

        record Item(UUID id, @NotNull UUID productoId, @NotNull @Positive BigDecimal cantidad, @Size(max = 200) String nota) {
        }

        List<NuevoPedido.NuevaLinea> lineas() {
            return items.stream().map(i -> new NuevoPedido.NuevaLinea(i.id(), i.productoId(), i.cantidad(), i.nota())).toList();
        }
    }

    record PedidoDeliveryResponse(UUID id, Integer numero, String estado, String estadoPago, BigDecimal subtotal, BigDecimal iva,
                                  BigDecimal propina, BigDecimal costoEnvio, BigDecimal total) {
        static PedidoDeliveryResponse de(PedidoVista v) {
            return new PedidoDeliveryResponse(v.id(), v.numero(), v.estado(), v.estadoPago().valor(), v.totales().subtotal(),
                    v.totales().iva(), v.totales().propina(), v.totales().costoEnvio(), v.totales().total());
        }
    }
}
