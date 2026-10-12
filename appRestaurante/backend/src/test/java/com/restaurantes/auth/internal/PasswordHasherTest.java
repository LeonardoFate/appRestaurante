package com.restaurantes.auth.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private PasswordHasher hasher;

    @BeforeEach
    void preparar() {
        hasher = new PasswordHasher();
        hasher.memoriaKb = 1024;     // parametros pequenos: la prueba es de logica, no de coste
        hasher.iteraciones = 1;
        hasher.paralelismo = 1;
        hasher.init();
    }

    @Test
    void generaUnHashArgon2idEnFormatoPhc() {
        String hash = hasher.hash("clave-segura-123");
        assertTrue(hash.startsWith("$argon2id$v=19$m=1024,t=1,p=1$"), hash);
        assertFalse(hash.contains("clave-segura-123"), "el hash no puede contener la contrasena");
    }

    @Test
    void verificaLaClaveCorrectaYRechazaLasDemas() {
        String hash = hasher.hash("clave-segura-123");
        assertTrue(hasher.verify("clave-segura-123", hash));
        assertFalse(hasher.verify("clave-segura-124", hash));
        assertFalse(hasher.verify("", hash));
        assertFalse(hasher.verify("CLAVE-SEGURA-123", hash));
    }

    @Test
    void cadaHashLlevaUnaSalDistinta() {
        assertNotEquals(hasher.hash("misma"), hasher.hash("misma"));
    }

    @Test
    void unHashGuardadoConOtrosParametrosSigueVerificando() {
        String hash = hasher.hash("clave-segura-123");
        hasher.memoriaKb = 2048;     // se subieron los parametros despues: los hashes viejos deben seguir sirviendo
        hasher.iteraciones = 2;
        assertTrue(hasher.verify("clave-segura-123", hash));
    }

    @Test
    void rechazaHashesAusentesOCorruptosSinLanzarExcepcion() {
        assertFalse(hasher.verify("x", null));
        assertFalse(hasher.verify("x", ""));
        assertFalse(hasher.verify("x", "no-es-un-hash"));
        assertFalse(hasher.verify("x", "$argon2id$v=19$m=abc,t=1,p=1$c2Fs$aGFzaA"));
        assertFalse(hasher.verify("x", "$bcrypt$v=19$m=1024,t=1,p=1$c2Fs$aGFzaA"));
    }

    @Test
    void rechazaParametrosAbusivosDeUnHashCorrupto() {
        // m = 4 GiB: no debe intentar reservar esa memoria
        assertFalse(hasher.verify("x", "$argon2id$v=19$m=4194304,t=1,p=1$c2FsdHNhbHRzYWx0c2FsdA$aGFzaGhhc2hoYXNoaGFzaGhhc2hoYXNoaGFzaGhhc2g"));
    }

    @Test
    void laVerificacionFalsaNoLanzaExcepcion() {
        hasher.verificarFalso("cualquiera");
    }
}
