package com.restaurantes.shared.web;

import com.restaurantes.shared.tenancy.TenantTransactionException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Ultima red: traduce errores de la base que no se previnieron antes a una respuesta segura.
 * El detalle SQL se registra en el log; al cliente solo llega un codigo generico.
 */
@Provider
public class TenantTransactionMapper implements ExceptionMapper<TenantTransactionException> {

    private static final Logger LOG = Logger.getLogger(TenantTransactionMapper.class);

    @Override
    public Response toResponse(TenantTransactionException e) {
        String sqlState = e.sqlState();
        int estado;
        String codigo;
        String mensaje;
        if ("23503".equals(sqlState)) {
            estado = 422;
            codigo = "REFERENCIA_INVALIDA";
            mensaje = "Una referencia del pedido no existe o no pertenece a este restaurante";
        } else if ("23505".equals(sqlState)) {
            estado = 409;
            codigo = "DUPLICADO";
            mensaje = "El recurso ya existe";
        } else if ("23514".equals(sqlState)) {
            estado = 409;
            codigo = "REGLA_DE_DATOS";
            mensaje = "La operacion incumple una regla de integridad";
        } else if ("42501".equals(sqlState)) {
            estado = 403;
            codigo = "PROHIBIDO";
            mensaje = "Operacion no permitida";
        } else {
            LOG.error("Error de base de datos no controlado (SQLSTATE " + sqlState + ")", e);
            estado = 500;
            codigo = "ERROR_INTERNO";
            mensaje = "Error interno";
        }
        return Response.status(estado)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorApi(codigo, mensaje))
                .build();
    }
}
