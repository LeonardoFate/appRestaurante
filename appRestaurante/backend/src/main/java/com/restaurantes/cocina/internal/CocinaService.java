package com.restaurantes.cocina.internal;

import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.pedidos.domain.EstadoCocina;
import com.restaurantes.pedidos.domain.EstadoPedido;
import com.restaurantes.salon.api.CatalogoMesas;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.security.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Casos de uso de cocina. Se ejecutan dentro de la unidad de trabajo del tenant (la abre el endpoint REST o el relay).
 *
 * <p>Cocina cambia el estado de cada ITEM; el estado del PEDIDO lo decide el modulo pedidos
 * ({@link PedidosApi#sincronizarConItems}). Para que dos cocineros que marcan items del mismo pedido a la vez no se
 * pisen, siempre se bloquea primero el pedido y despues el item (mismo orden en todos los casos de uso).
 */
@ApplicationScoped
class CocinaService {

    @Inject
    CocinaRepository repo;

    @Inject
    PedidosApi pedidos;

    @Inject
    CatalogoMesas mesas;

    @Inject
    CallerContext caller;

    // ------------------------------------------------------------------ lectura

    List<EstacionResponse> estaciones() {
        return repo.estaciones(caller.sucursalId()).stream().map(e -> new EstacionResponse(e.id(), e.nombre())).toList();
    }

    List<ItemCocinaResponse> itemsActivos(UUID estacionId) {
        return aRespuestas(repo.itemsActivos(caller.sucursalId(), estacionId));
    }

    // ----------------------------------------------------------------- comandos

    /**
     * Cambia el estado de un item. Idempotente: pedir el estado que ya tiene no hace nada. Solo ve y toca items de la
     * sucursal del token (los de otra sucursal o restaurante "no existen").
     */
    ItemCocinaResponse avanzar(UUID itemId, EstadoCocina destino, String motivo) {
        UUID sucursal = caller.sucursalId();
        UUID usuario = caller.usuarioId();

        CocinaRepository.ItemBase previo = repo.base(itemId, false)
                .filter(b -> b.sucursalId().equals(sucursal))
                .orElseThrow(CocinaService::itemNoEncontrado);

        // Primero el pedido, despues el item: mismo orden siempre, para no provocar bloqueos cruzados.
        PedidoVista pedido = pedidos.buscarBloqueando(previo.pedidoId())
                .orElseThrow(CocinaService::itemNoEncontrado);
        CocinaRepository.ItemBase item = repo.base(itemId, true).orElseThrow(CocinaService::itemNoEncontrado);

        if (item.estado() == destino) {
            return unica(itemId);
        }
        EstadoPedido estadoPedido = EstadoPedido.desde(pedido.estado());
        if (estadoPedido.esFinal() || estadoPedido.estaAnulado()) {
            throw ReglaDeNegocioException.conflicto("PEDIDO_NO_ACTIVO", "El pedido esta " + estadoPedido.valor());
        }
        if (!item.estado().puedePasarA(destino)) {
            throw ReglaDeNegocioException.conflicto("TRANSICION_INVALIDA",
                    "Un item '" + item.estado().valor() + "' no puede pasar a '" + destino.valor() + "'");
        }
        if (destino == EstadoCocina.ANULADO) {
            if (motivo == null || motivo.isBlank()) {
                throw ReglaDeNegocioException.invalido("MOTIVO_REQUERIDO", "Indique el motivo de la anulacion");
            }
            // Anular cambia el total del pedido: con cobros hay que reembolsar primero.
            if (!pedido.estadoPago().sinDineroCobrado()) {
                throw ReglaDeNegocioException.conflicto("PEDIDO_CON_COBROS",
                        "El pedido tiene cobros: reembolse primero lo cobrado y despues anule el item");
            }
        }

        repo.cambiarEstado(itemId, destino, motivo == null ? null : motivo.trim(), usuario);
        pedidos.sincronizarConItems(pedido.id(), usuario);
        return unica(itemId);
    }

    // ------------------------------------------------ reaccion a eventos (relay)

    /** {@code PedidoConfirmado}: los items pasan a "enviado" y se asigna estacion a los que no tienen. */
    void enviarAPedido(UUID pedidoId) {
        Optional<PedidoVista> encontrado = pedidos.buscarBloqueando(pedidoId);
        if (encontrado.isEmpty()) {
            return;
        }
        PedidoVista pedido = encontrado.get();
        if (EstadoPedido.desde(pedido.estado()).estaAnulado()) {
            return;   // se cancelo antes de que llegara el evento
        }
        UUID estacion = repo.estacionPorDefecto(pedido.sucursalId()).orElse(null);
        repo.enviarItems(pedidoId, estacion);
        pedidos.sincronizarConItems(pedidoId, null);
    }

    /** {@code PedidoCancelado}: se anulan los items que no se hayan servido. */
    void anularItemsDeCancelado(UUID pedidoId, String motivo, UUID usuarioId) {
        pedidos.buscarBloqueando(pedidoId);
        repo.anularItemsDeCancelado(pedidoId, motivo, usuarioId);
    }

    // ---------------------------------------------------------------- helpers

    private ItemCocinaResponse unica(UUID itemId) {
        CocinaRepository.ItemKds item = repo.vista(itemId).orElseThrow(CocinaService::itemNoEncontrado);
        return aRespuestas(List.of(item)).get(0);
    }

    private List<ItemCocinaResponse> aRespuestas(List<CocinaRepository.ItemKds> items) {
        Set<UUID> mesaIds = items.stream().map(CocinaRepository.ItemKds::mesaId).filter(m -> m != null).collect(Collectors.toSet());
        Map<UUID, String> codigos = mesas.codigos(mesaIds);
        OffsetDateTime ahora = OffsetDateTime.now();
        return items.stream().map(i -> new ItemCocinaResponse(i.itemId(), i.pedidoId(), i.numeroPedido(), i.canal().valor(),
                i.mesaId() == null ? null : codigos.get(i.mesaId()), i.nombre(), i.cantidad(), i.nota(), i.estado().valor(),
                i.estacionId(), i.tiempoServicio(), i.enviadoEn(),
                i.enviadoEn() == null ? 0 : Math.max(0, Duration.between(i.enviadoEn(), ahora).toSeconds()))).toList();
    }

    private static ReglaDeNegocioException itemNoEncontrado() {
        return ReglaDeNegocioException.noEncontrado("ITEM_NO_ENCONTRADO", "El item no existe");
    }
}
