package com.restaurantes.shared.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Identidad del llamante, tomada SOLO del JWT ya verificado (nunca de cabeceras ni del cuerpo).
 * Claims: {@code sub} = usuario, {@code tenant_id}, {@code sucursal_id}.
 */
@RequestScoped
public class CallerContext {

    @Inject
    JsonWebToken jwt;

    public UUID tenantId() {
        return claimUuid("tenant_id");
    }

    public UUID sucursalId() {
        return claimUuid("sucursal_id");
    }

    public UUID usuarioId() {
        return parse("sub", jwt.getSubject());
    }

    private UUID claimUuid(String nombre) {
        Object valor = jwt.getClaim(nombre);
        return parse(nombre, valor == null ? null : valor.toString());
    }

    private static UUID parse(String nombre, String valor) {
        if (valor == null || valor.isBlank()) {
            throw new ForbiddenException("El token no incluye '" + nombre + "'");
        }
        try {
            return UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            throw new ForbiddenException("El claim '" + nombre + "' del token no es un UUID valido");
        }
    }
}
