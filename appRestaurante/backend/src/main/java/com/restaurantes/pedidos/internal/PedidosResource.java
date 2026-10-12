package com.restaurantes.pedidos.internal;

import com.restaurantes.pedidos.api.PedidoVista;
import com.restaurantes.pedidos.api.PedidosApi;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
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

/** Adaptador HTTP del modulo pedidos. El tenant y el usuario salen del JWT, nunca del cuerpo. */
@Path("/api/v1/pedidos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"owner", "admin", "cajero", "mesero"})
public class PedidosResource {

    @Inject
    PedidosApi pedidos;

    /** 201 si lo crea; 200 si es la repeticion idempotente de una orden ya procesada. */
    @POST
    public Response crear(@NotNull @Valid CrearPedidoRequest request) {
        PedidoVista vista = pedidos.crear(request.aOrden());
        return Response.status(vista.creadoAhora() ? 201 : 200).entity(PedidoResponse.de(vista)).build();
    }

    @GET
    @Path("/{id}")
    public PedidoResponse obtener(@PathParam("id") UUID id) {
        return pedidos.buscar(id)
                .map(PedidoResponse::de)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe"));
    }
}
