package com.restaurantes.shared.errors;

/**
 * Violacion de una regla de negocio. Java puro: la usa el dominio y la traduce a HTTP el adaptador
 * web ({@code shared.web}). El mensaje es para el cliente, asi que no debe incluir detalles internos.
 */
public class ReglaDeNegocioException extends RuntimeException {

    public enum Tipo {
        /** El recurso no existe (o no es visible para este tenant). */
        NO_ENCONTRADO,
        /** El estado actual no permite la operacion. */
        CONFLICTO,
        /** La peticion es valida en forma pero incumple una regla. */
        INVALIDO,
        /** No se pudo establecer quien es el llamante (credenciales o token invalidos). */
        NO_AUTENTICADO,
        /** El llamante es quien dice ser, pero no tiene acceso. */
        SIN_ACCESO
    }

    private final Tipo tipo;
    private final String codigo;

    public ReglaDeNegocioException(Tipo tipo, String codigo, String mensaje) {
        super(mensaje);
        this.tipo = tipo;
        this.codigo = codigo;
    }

    public Tipo tipo() {
        return tipo;
    }

    public String codigo() {
        return codigo;
    }

    public static ReglaDeNegocioException noEncontrado(String codigo, String mensaje) {
        return new ReglaDeNegocioException(Tipo.NO_ENCONTRADO, codigo, mensaje);
    }

    public static ReglaDeNegocioException conflicto(String codigo, String mensaje) {
        return new ReglaDeNegocioException(Tipo.CONFLICTO, codigo, mensaje);
    }

    public static ReglaDeNegocioException invalido(String codigo, String mensaje) {
        return new ReglaDeNegocioException(Tipo.INVALIDO, codigo, mensaje);
    }

    public static ReglaDeNegocioException noAutenticado(String codigo, String mensaje) {
        return new ReglaDeNegocioException(Tipo.NO_AUTENTICADO, codigo, mensaje);
    }

    public static ReglaDeNegocioException sinAcceso(String codigo, String mensaje) {
        return new ReglaDeNegocioException(Tipo.SIN_ACCESO, codigo, mensaje);
    }
}
