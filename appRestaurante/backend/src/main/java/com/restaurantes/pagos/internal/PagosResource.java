package com.restaurantes.pagos.internal;

import com.restaurantes.shared.security.CallerContext;
import com.restaurantes.shared.tenancy.TenantTransaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/** Adaptador HTTP del modulo pagos. Abre la unidad de trabajo del tenant del token. */
@Path("/api/v1/pagos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"owner", "admin", "cajero", "mesero"})
public class PagosResource {

    @Inject
    PagosService pagos;

    @Inject
    ReembolsosService reembolsos;

    @Inject
    TenantTransaction tx;

    @Inject
    CallerContext caller;

    /** 201 si registra el cobro; 200 si es la repeticion idempotente de un pago ya registrado. */
    @POST
    public Response registrar(@NotNull @Valid RegistrarPagoRequest request) {
        var metodo = request.metodoPago();
        ResultadoPago resultado = tx.withTenant(caller.tenantId(),
                c -> pagos.registrar(request.id(), request.pedidoId(), metodo, request.monto()));
        return Response.status(resultado.creadoAhora() ? 201 : 200).entity(PagoResponse.de(resultado)).build();
    }

    /**
     * Devuelve dinero de un pago. Es una accion sensible: solo owner y admin, siempre con motivo, y queda en la
     * auditoria. 201 si lo registra; 200 si es la repeticion idempotente de un reembolso ya registrado.
     */
    @POST
    @Path("/{pagoId}/reembolsos")
    @RolesAllowed({"owner", "admin"})
    public Response reembolsar(@PathParam("pagoId") UUID pagoId, @NotNull @Valid ReembolsarRequest request) {
        UUID usuario = caller.usuarioId();
        ResultadoReembolso resultado = tx.withTenant(caller.tenantId(),
                c -> reembolsos.reembolsar(request.id(), pagoId, request.monto(), request.motivo(), usuario));
        return Response.status(resultado.creadoAhora() ? 201 : 200).entity(ReembolsoResponse.de(resultado)).build();
    }
}
