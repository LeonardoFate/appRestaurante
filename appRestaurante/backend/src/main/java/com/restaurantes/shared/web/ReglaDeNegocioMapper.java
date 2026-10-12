package com.restaurantes.shared.web;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Traduce las reglas de negocio violadas a HTTP: 404, 409 o 422. */
@Provider
public class ReglaDeNegocioMapper implements ExceptionMapper<ReglaDeNegocioException> {

    @Override
    public Response toResponse(ReglaDeNegocioException e) {
        int estado = switch (e.tipo()) {
            case NO_ENCONTRADO -> 404;
            case CONFLICTO -> 409;
            case INVALIDO -> 422;
            case NO_AUTENTICADO -> 401;
            case SIN_ACCESO -> 403;
        };
        return Response.status(estado)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorApi(e.codigo(), e.getMessage()))
                .build();
    }
}
