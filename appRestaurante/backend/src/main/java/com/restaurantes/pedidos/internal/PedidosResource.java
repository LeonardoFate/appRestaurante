package com.restaurantes.pedidos.internal;

import com.restaurantes.pedidos.api.NuevoPedido;
import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.security.CallerContext;
import com.restaurantes.shared.tenancy.TenantTransaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/**
 * Adaptador HTTP del modulo pedidos. Abre la unidad de trabajo del tenant del token (el borde del caso de uso);
 * el tenant y el usuario salen del JWT, nunca del cuerpo.
 */
@Path("/api/v1/pedidos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"owner", "admin", "cajero", "mesero"})
public class PedidosResource {

    @Inject
    PedidosApi pedidos;

    @Inject
    TenantTransaction tx;

    @Inject
    CallerContext caller;

    /** 201 si lo crea; 200 si es la repeticion idempotente de una orden ya procesada. */
    @POST
    public Response crear(@NotNull @Valid CrearPedidoRequest request) {
        NuevoPedido orden = request.aOrden();
        PedidoVista vista = tx.withTenant(caller.tenantId(), c -> pedidos.crear(orden));
        return Response.status(vista.creadoAhora() ? 201 : 200).entity(PedidoResponse.de(vista)).build();
    }

    @GET
    @Path("/{id}")
    public PedidoResponse obtener(@PathParam("id") UUID id) {
        return tx.withTenant(caller.tenantId(), c -> pedidos.buscar(id))
                .map(PedidoResponse::de)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe"));
    }

    /**
     * Confirma el pedido: lo manda a cocina. Idempotente (repetirlo devuelve el pedido sin cambios). Cocina recibe los
     * items por el evento {@code PedidoConfirmado}, con unos segundos como maximo de retraso.
     */
    @POST
    @Path("/{id}/confirmar")
    public PedidoResponse confirmar(@PathParam("id") UUID id) {
        UUID usuario = caller.usuarioId();
        return PedidoResponse.de(tx.withTenant(caller.tenantId(), c -> pedidos.confirmar(id, usuario)));
    }

    /** Cancela el pedido con un motivo. Solo antes de entregar y sin cobros. Requiere permiso de caja o administracion. */
    @POST
    @Path("/{id}/cancelar")
    @RolesAllowed({"owner", "admin", "cajero"})
    public PedidoResponse cancelar(@PathParam("id") UUID id, @NotNull @Valid CancelarPedidoRequest request) {
        UUID usuario = caller.usuarioId();
        return PedidoResponse.de(tx.withTenant(caller.tenantId(), c -> pedidos.cancelar(id, request.motivo(), usuario)));
    }
}
