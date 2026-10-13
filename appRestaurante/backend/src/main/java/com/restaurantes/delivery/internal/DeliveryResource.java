package com.restaurantes.delivery.internal;

import com.restaurantes.delivery.internal.DeliveryDtos.ClienteResponse;
import com.restaurantes.delivery.internal.DeliveryDtos.CrearClienteRequest;
import com.restaurantes.delivery.internal.DeliveryDtos.CrearDireccionRequest;
import com.restaurantes.delivery.internal.DeliveryDtos.CrearPedidoDeliveryRequest;
import com.restaurantes.delivery.internal.DeliveryDtos.DireccionResponse;
import com.restaurantes.delivery.internal.DeliveryDtos.PedidoDeliveryResponse;
import com.restaurantes.delivery.internal.DeliveryDtos.ZonaResponse;
import com.restaurantes.pedidos.api.PedidoVista;
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
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/** Adaptador HTTP del modulo delivery. Abre la unidad de trabajo del tenant del token. */
@Path("/api/v1/delivery")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"owner", "admin", "cajero"})
public class DeliveryResource {

    @Inject
    DeliveryService delivery;

    @Inject
    TenantTransaction tx;

    @Inject
    CallerContext caller;

    @GET
    @Path("/zonas")
    public List<ZonaResponse> zonas() {
        return tx.withTenant(caller.tenantId(), c -> delivery.zonasDeLaSucursal()).stream().map(ZonaResponse::de).toList();
    }

    @POST
    @Path("/clientes")
    public Response crearCliente(@NotNull @Valid CrearClienteRequest request) {
        DeliveryService.ClienteConDirecciones r = tx.withTenant(caller.tenantId(), c -> delivery.crearCliente(request.id(),
                request.nombre(), request.tipoIdentificacion(), request.identificacion(), request.telefono(), request.email()));
        return Response.status(r.creadoAhora() ? 201 : 200).entity(ClienteResponse.de(r.cliente(), r.direcciones())).build();
    }

    @GET
    @Path("/clientes")
    public List<ClienteResponse> buscarClientes(@QueryParam("telefono") String telefono) {
        return tx.withTenant(caller.tenantId(), c -> delivery.buscarPorTelefono(telefono)).stream()
                .map(cl -> ClienteResponse.de(cl, List.of())).toList();
    }

    @GET
    @Path("/clientes/{id}")
    public ClienteResponse cliente(@PathParam("id") UUID id) {
        DeliveryService.ClienteConDirecciones r = tx.withTenant(caller.tenantId(), c -> delivery.obtenerCliente(id));
        return ClienteResponse.de(r.cliente(), r.direcciones());
    }

    @POST
    @Path("/clientes/{id}/direcciones")
    public Response agregarDireccion(@PathParam("id") UUID clienteId, @NotNull @Valid CrearDireccionRequest request) {
        DeliveryService.DireccionCreada r = tx.withTenant(caller.tenantId(),
                c -> delivery.agregarDireccion(clienteId, request.id(), request.etiqueta(), request.direccion(), request.referencia()));
        return Response.status(r.creadaAhora() ? 201 : 200).entity(DireccionResponse.de(r.direccion())).build();
    }

    /** 201 si lo crea; 200 si es la repeticion idempotente de una orden ya procesada. */
    @POST
    @Path("/pedidos")
    public Response crearPedido(@NotNull @Valid CrearPedidoDeliveryRequest request) {
        PedidoVista vista = tx.withTenant(caller.tenantId(), c -> delivery.crearPedido(request.id(), request.clienteId(),
                request.direccionId(), request.zonaId(), request.momentoPago(), request.propina(), request.lineas()));
        return Response.status(vista.creadoAhora() ? 201 : 200).entity(PedidoDeliveryResponse.de(vista)).build();
    }
}
