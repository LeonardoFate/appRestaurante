package com.restaurantes.shared.jobs;

/** Fallo que no tiene sentido reintentar (datos invalidos, regla incumplida): el job pasa a "fallido" de inmediato. */
public class JobPermanenteException extends RuntimeException {

    public JobPermanenteException(String mensaje) {
        super(mensaje);
    }

    public JobPermanenteException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
