package com.restaurantes.delivery.internal;

import com.restaurantes.delivery.internal.EntregasDtos.AsignarRequest;
import com.restaurantes.delivery.internal.EntregasDtos.EntregaResponse;
import com.restaurantes.delivery.internal.EntregasDtos.FallarRequest;
import com.restaurantes.delivery.internal.EntregasDtos.LiquidacionResponse;
import com.restaurantes.delivery.internal.EntregasDtos.LiquidarRequest;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reparto: el despacho (caja y administracion) asigna y liquida; el repartidor recoge, entrega y reporta fallos. Abre la
 * unidad de trabajo del tenant del token.
 */
@Path("/api/v1/delivery")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EntregasResource {

    private static final Set<String> ESTADOS = Set.of("pendiente", "asignado", "recogido", "entregado", "fallido", "devuelto_a_local");

    @Inject
    EntregasService entregas;

    @Inject
    TenantTransaction tx;

    @Inject
    CallerContext caller;

    // ------------------------------------------------------------- despacho

    /** Entregas de la sucursal; por omision, las que aun requieren atencion. */
    @GET
    @Path("/entregas")
    @RolesAllowed({"owner", "admin", "cajero"})
    public List<EntregaResponse> listar(@QueryParam("estado") String estado) {
        List<String> estados = estado == null || estado.isBlank() ? List.of("pendiente", "asignado", "recogido") : List.of(estado);
        if (!ESTADOS.containsAll(estados)) {
            throw ReglaDeNegocioException.invalido("ESTADO_INVALIDO", "Estado de entrega desconocido: " + estado);
        }
        return tx.withTenant(caller.tenantId(), c -> entregas.listar(estados)).stream().map(EntregaResponse::de).toList();
    }

    @POST
    @Path("/entregas/{pedidoId}/asignar")
    @RolesAllowed({"owner", "admin", "cajero"})
    public EntregaResponse asignar(@PathParam("pedidoId") UUID pedidoId, @NotNull @Valid AsignarRequest request) {
        return EntregaResponse.de(tx.withTenant(caller.tenantId(), c -> entregas.asignar(pedidoId, request.repartidorId())));
    }

    // ----------------------------------------------------------- repartidor

    @GET
    @Path("/entregas/mias")
    @RolesAllowed("repartidor")
    public List<EntregaResponse> mias() {
        return tx.withTenant(caller.tenantId(), c -> entregas.misEntregas()).stream().map(EntregaResponse::de).toList();
    }

    @POST
    @Path("/entregas/{pedidoId}/recoger")
    @RolesAllowed("repartidor")
    public EntregaResponse recoger(@PathParam("pedidoId") UUID pedidoId) {
        return EntregaResponse.de(tx.withTenant(caller.tenantId(), c -> entregas.recoger(pedidoId)));
    }

    @POST
    @Path("/entregas/{pedidoId}/entregar")
    @RolesAllowed("repartidor")
    public EntregaResponse entregar(@PathParam("pedidoId") UUID pedidoId) {
        return EntregaResponse.de(tx.withTenant(caller.tenantId(), c -> entregas.entregar(pedidoId)));
    }

    @POST
    @Path("/entregas/{pedidoId}/fallar")
    @RolesAllowed("repartidor")
    public EntregaResponse fallar(@PathParam("pedidoId") UUID pedidoId, @NotNull @Valid FallarRequest request) {
        return EntregaResponse.de(tx.withTenant(caller.tenantId(), c -> entregas.fallar(pedidoId, request.motivo())));
    }

    // ----------------------------------------------------------- liquidacion

    /** Efectivo cobrado por cada repartidor y aun no entregado a caja. */
    @GET
    @Path("/liquidaciones/pendientes")
    @RolesAllowed({"owner", "admin", "cajero"})
    public List<LiquidacionResponse> pendientes() {
        return tx.withTenant(caller.tenantId(), c -> entregas.pendientesDeLiquidar()).stream().map(LiquidacionResponse::de).toList();
    }

    /** Caja recibe el efectivo del repartidor. Repetirla no vuelve a contar lo ya liquidado. */
    @POST
    @Path("/liquidaciones")
    @RolesAllowed({"owner", "admin", "cajero"})
    public LiquidacionResponse liquidar(@NotNull @Valid LiquidarRequest request) {
        return LiquidacionResponse.de(tx.withTenant(caller.tenantId(), c -> entregas.liquidar(request.repartidorId())));
    }
}
