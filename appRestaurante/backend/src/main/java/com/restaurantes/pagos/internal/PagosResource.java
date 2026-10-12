package com.restaurantes.pagos.internal;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Adaptador HTTP del modulo pagos. */
@Path("/api/v1/pagos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"owner", "admin", "cajero", "mesero"})
public class PagosResource {

    @Inject
    PagosService pagos;

    /** 201 si registra el cobro; 200 si es la repeticion idempotente de un pago ya registrado. */
    @POST
    public Response registrar(@NotNull @Valid RegistrarPagoRequest request) {
        ResultadoPago resultado = pagos.registrar(request.id(), request.pedidoId(), request.metodoPago(), request.monto());
        return Response.status(resultado.creadoAhora() ? 201 : 200).entity(PagoResponse.de(resultado)).build();
    }
}
