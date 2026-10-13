package com.restaurantes.delivery.internal;

import com.restaurantes.delivery.domain.MomentoDePago;
import com.restaurantes.delivery.domain.TipoIdentificacion;
import com.restaurantes.pedidos.api.NuevoPedido;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.CanalPedido;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.security.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos de uso de delivery: clientes, direcciones, zonas y la toma del pedido a domicilio. Se ejecuta dentro de la
 * unidad de trabajo del tenant que abre el endpoint. El costo de envio y el pedido minimo salen de la zona, nunca del
 * cliente HTTP.
 */
@ApplicationScoped
class DeliveryService {

    record ClienteConDirecciones(ClienteRepository.ClienteFila cliente, List<ClienteRepository.DireccionFila> direcciones,
                                 boolean creadoAhora) {
    }

    record DireccionCreada(ClienteRepository.DireccionFila direccion, boolean creadaAhora) {
    }

    @Inject
    ClienteRepository clientes;

    @Inject
    ZonaRepository zonas;

    @Inject
    PedidosApi pedidos;

    @Inject
    CallerContext caller;

    // ---------------------------------------------------------------- clientes

    ClienteConDirecciones crearCliente(UUID idSolicitado, String nombre, String tipo, String identificacion, String telefono,
                                       String email) {
        if (nombre == null || nombre.isBlank()) {
            throw ReglaDeNegocioException.invalido("NOMBRE_REQUERIDO", "El nombre del cliente es obligatorio");
        }
        if (telefono == null || telefono.isBlank()) {
            throw ReglaDeNegocioException.invalido("TELEFONO_REQUERIDO", "El telefono es obligatorio para un domicilio");
        }
        TipoIdentificacion.validar(tipo, identificacion);
        UUID id = idSolicitado != null ? idSolicitado : UuidV7.next();

        clientes.bloquear("cliente:" + id);
        Optional<ClienteRepository.ClienteFila> existente = clientes.cliente(id);
        if (existente.isPresent()) {
            ClienteRepository.ClienteFila c = existente.get();
            if (!c.tipoIdentificacion().equals(tipo) || !c.identificacion().equals(identificacion.trim())) {
                throw ReglaDeNegocioException.conflicto("CLIENTE_ID_REUTILIZADO", "El id del cliente ya se uso con otros datos");
            }
            return new ClienteConDirecciones(c, clientes.direcciones(id), false);
        }
        // La identificacion es unica por restaurante: serializa dos altas simultaneas del mismo cliente.
        clientes.bloquear("cliente-identificacion:" + tipo + ":" + identificacion.trim());
        if (clientes.clientePorIdentificacion(tipo, identificacion.trim()).isPresent()) {
            throw ReglaDeNegocioException.conflicto("CLIENTE_YA_EXISTE", "Ya hay un cliente con esa identificacion");
        }
        clientes.insertarCliente(id, nombre.trim(), tipo, identificacion.trim(), telefono.trim(),
                email == null || email.isBlank() ? null : email.trim());
        return new ClienteConDirecciones(clientes.cliente(id).orElseThrow(), List.of(), true);
    }

    ClienteConDirecciones obtenerCliente(UUID id) {
        ClienteRepository.ClienteFila c = clientes.cliente(id)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("CLIENTE_NO_ENCONTRADO", "El cliente no existe"));
        return new ClienteConDirecciones(c, clientes.direcciones(id), false);
    }

    List<ClienteRepository.ClienteFila> buscarPorTelefono(String telefono) {
        if (telefono == null || telefono.isBlank()) {
            throw ReglaDeNegocioException.invalido("TELEFONO_REQUERIDO", "Indique el telefono a buscar");
        }
        return clientes.buscarPorTelefono(telefono.trim());
    }

    DireccionCreada agregarDireccion(UUID clienteId, UUID idSolicitado, String etiqueta, String direccion, String referencia) {
        if (direccion == null || direccion.isBlank()) {
            throw ReglaDeNegocioException.invalido("DIRECCION_REQUERIDA", "La direccion es obligatoria");
        }
        clientes.cliente(clienteId)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("CLIENTE_NO_ENCONTRADO", "El cliente no existe"));
        UUID id = idSolicitado != null ? idSolicitado : UuidV7.next();

        clientes.bloquear("direccion:" + id);
        Optional<ClienteRepository.DireccionFila> existente = clientes.direccion(id);
        if (existente.isPresent()) {
            if (!existente.get().clienteId().equals(clienteId)) {
                throw ReglaDeNegocioException.conflicto("DIRECCION_ID_REUTILIZADO", "El id de la direccion ya se uso con otros datos");
            }
            return new DireccionCreada(existente.get(), false);
        }
        clientes.insertarDireccion(id, clienteId, etiqueta == null || etiqueta.isBlank() ? "Casa" : etiqueta.trim(),
                direccion.trim(), referencia == null || referencia.isBlank() ? null : referencia.trim());
        return new DireccionCreada(clientes.direccion(id).orElseThrow(), true);
    }

    // ------------------------------------------------------------------- zonas

    List<ZonaRepository.ZonaFila> zonasDeLaSucursal() {
        return zonas.activasDe(caller.sucursalId());
    }

    // ----------------------------------------------------------------- pedidos

    /** Toma un pedido a domicilio: valida cliente, direccion y zona, fija el envio y delega la creacion en {@code pedidos}. */
    PedidoVista crearPedido(UUID id, UUID clienteId, UUID direccionId, UUID zonaId, String momentoPago, BigDecimal propina,
                            List<NuevoPedido.NuevaLinea> lineas) {
        UUID pedidoId = id != null ? id : UuidV7.next();
        // Repetir una orden ya procesada devuelve el pedido original, aunque la zona haya cambiado desde entonces.
        Optional<PedidoVista> existente = pedidos.buscar(pedidoId);
        if (existente.isPresent()) {
            return existente.get().comoRepeticion();
        }

        MomentoDePago momento = MomentoDePago.desde(momentoPago);
        clientes.cliente(clienteId)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("CLIENTE_NO_ENCONTRADO", "El cliente no existe"));
        ClienteRepository.DireccionFila direccion = clientes.direccion(direccionId)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("DIRECCION_NO_ENCONTRADA", "La direccion no existe"));
        if (!direccion.clienteId().equals(clienteId)) {
            throw ReglaDeNegocioException.invalido("DIRECCION_NO_ES_DEL_CLIENTE", "La direccion no pertenece a ese cliente");
        }
        ZonaRepository.ZonaFila zona = zonas.activa(zonaId, caller.sucursalId())
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("ZONA_NO_ENCONTRADA",
                        "La zona no existe o no esta activa en esta sucursal"));

        NuevoPedido orden = new NuevoPedido(pedidoId, CanalPedido.DELIVERY_PROPIO, null, propina, lineas,
                new NuevoPedido.Envio(clienteId, direccionId, zona.costoEnvio(), zona.pedidoMinimo(), momento.valor()));
        return pedidos.crear(orden);
    }
}
