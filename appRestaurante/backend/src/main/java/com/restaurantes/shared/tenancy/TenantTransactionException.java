package com.restaurantes.shared.tenancy;

import java.sql.SQLException;

/** Error al ejecutar trabajo dentro de la transaccion de un tenant. */
public class TenantTransactionException extends RuntimeException {

    public TenantTransactionException(String message, SQLException cause) {
        super(message, cause);
    }

    /** SQLSTATE original (p. ej. 23505 clave duplicada, 23514 CHECK), o null. */
    public String sqlState() {
        return getCause() instanceof SQLException sql ? sql.getSQLState() : null;
    }
}
