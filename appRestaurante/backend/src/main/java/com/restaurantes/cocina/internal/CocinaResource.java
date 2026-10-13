package com.restaurantes.cocina.internal;

import com.restaurantes.pedidos.domain.EstadoCocina;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador HTTP de cocina: lo que consume la pantalla de cocina (KDS) y los meseros. Solo se ven y tocan los items
 * de la sucursal del token.
 */
@Path("/api/v1/cocina")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CocinaResource {

    @Inject
    CocinaService cocina;

    @Inject
    TenantTransaction tx;

    @Inject
    CallerContext caller;

    @GET
    @Path("/estaciones")
    @RolesAllowed({"cocina", "admin", "owner", "mesero", "cajero"})
    public List<EstacionResponse> estaciones() {
        return tx.withTenant(caller.tenantId(), c -> cocina.estaciones());
    }

    /** Items en curso (enviados, preparando o listos), del mas antiguo al mas reciente; opcionalmente de una estacion. */
    @GET
    @Path("/items")
    @RolesAllowed({"cocina", "admin", "owner", "mesero", "cajero"})
    public List<ItemCocinaResponse> items(@QueryParam("estacion") UUID estacion) {
        return tx.withTenant(caller.tenantId(), c -> cocina.itemsActivos(estacion));
    }

    @POST
    @Path("/items/{id}/preparando")
    @RolesAllowed({"cocina", "admin", "owner"})
    public ItemCocinaResponse preparando(@PathParam("id") UUID id) {
        return avanzar(id, EstadoCocina.PREPARANDO, null);
    }

    @POST
    @Path("/items/{id}/listo")
    @RolesAllowed({"cocina", "admin", "owner"})
    public ItemCocinaResponse listo(@PathParam("id") UUID id) {
        return avanzar(id, EstadoCocina.LISTO, null);
    }

    @POST
    @Path("/items/{id}/servido")
    @RolesAllowed({"mesero", "cajero", "cocina", "admin", "owner"})
    public ItemCocinaResponse servido(@PathParam("id") UUID id) {
        return avanzar(id, EstadoCocina.SERVIDO, null);
    }

    /** Anula un item con motivo. Cambia el total del pedido, por eso exige permiso de caja o administracion. */
    @POST
    @Path("/items/{id}/anular")
    @RolesAllowed({"owner", "admin", "cajero"})
    public ItemCocinaResponse anular(@PathParam("id") UUID id, @NotNull @Valid AnularItemRequest request) {
        return avanzar(id, EstadoCocina.ANULADO, request.motivo());
    }

    private ItemCocinaResponse avanzar(UUID id, EstadoCocina destino, String motivo) {
        return tx.withTenant(caller.tenantId(), c -> cocina.avanzar(id, destino, motivo));
    }
}
