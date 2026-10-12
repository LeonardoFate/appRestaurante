package com.restaurantes.auth.internal;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Endpoints publicos de sesion. Nunca se cachean: transportan credenciales y tokens. */
@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
public class AuthResource {

    @Inject
    AuthService auth;

    @POST
    @Path("/login")
    public Response login(@NotNull @Valid LoginRequest request) {
        return sinCache(Response.ok(auth.login(request.email(), request.password(), request.tenantId(), request.sucursalId())));
    }

    @POST
    @Path("/refresh")
    public Response renovar(@NotNull @Valid RefreshRequest request) {
        return sinCache(Response.ok(auth.renovar(request.refreshToken())));
    }

    @POST
    @Path("/logout")
    public Response cerrarSesion(@NotNull @Valid RefreshRequest request) {
        auth.cerrarSesion(request.refreshToken());
        return sinCache(Response.noContent());
    }

    private static Response sinCache(Response.ResponseBuilder respuesta) {
        return respuesta.header("Cache-Control", "no-store").header("Pragma", "no-cache").build();
    }
}
