package com.restaurantes.auth.internal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Puente de pruebas: {@code PasswordHasher} es privado del modulo auth, pero los escenarios necesitan crear usuarios con login real. */
@ApplicationScoped
public class HashParaPruebas {

    @Inject
    PasswordHasher hasher;

    public String hash(String password) {
        return hasher.hash(password);
    }
}
