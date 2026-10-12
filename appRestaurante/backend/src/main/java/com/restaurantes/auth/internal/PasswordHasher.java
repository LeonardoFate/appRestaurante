package com.restaurantes.auth.internal;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Hash de contrasenas con Argon2id en formato PHC ({@code $argon2id$v=19$m=...,t=...,p=...$sal$hash}).
 * Los parametros viajan dentro del hash, asi se pueden subir sin invalidar los existentes.
 */
@ApplicationScoped
class PasswordHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();
    private static final int SAL_BYTES = 16;
    private static final int HASH_BYTES = 32;
    // Limites de los parametros leidos de un hash almacenado (defensa ante datos corruptos).
    private static final int MAX_MEMORIA_KB = 1 << 20;
    private static final int MAX_ITERACIONES = 20;
    private static final int MAX_PARALELISMO = 16;

    @ConfigProperty(name = "app.auth.argon2.memoria-kb")
    int memoriaKb;

    @ConfigProperty(name = "app.auth.argon2.iteraciones")
    int iteraciones;

    @ConfigProperty(name = "app.auth.argon2.paralelismo")
    int paralelismo;

    private String hashFalso;

    @PostConstruct
    void init() {
        // Hash de una clave aleatoria: sirve para que "usuario inexistente" tarde lo mismo que "clave incorrecta".
        byte[] aleatoria = new byte[24];
        RANDOM.nextBytes(aleatoria);
        hashFalso = hash(B64.encodeToString(aleatoria));
    }

    String hash(String password) {
        byte[] sal = new byte[SAL_BYTES];
        RANDOM.nextBytes(sal);
        byte[] resultado = calcular(password, sal, memoriaKb, iteraciones, paralelismo);
        return "$argon2id$v=19$m=" + memoriaKb + ",t=" + iteraciones + ",p=" + paralelismo
                + "$" + B64.encodeToString(sal) + "$" + B64.encodeToString(resultado);
    }

    boolean verify(String password, String hashAlmacenado) {
        if (hashAlmacenado == null || hashAlmacenado.isBlank()) {
            return false;
        }
        try {
            String[] partes = hashAlmacenado.split("\\$");
            // ["", "argon2id", "v=19", "m=..,t=..,p=..", sal, hash]
            if (partes.length != 6 || !"argon2id".equals(partes[1]) || !"v=19".equals(partes[2])) {
                return false;
            }
            int m = 0;
            int t = 0;
            int p = 0;
            for (String par : partes[3].split(",")) {
                String[] kv = par.split("=");
                switch (kv[0]) {
                    case "m" -> m = Integer.parseInt(kv[1]);
                    case "t" -> t = Integer.parseInt(kv[1]);
                    case "p" -> p = Integer.parseInt(kv[1]);
                    default -> {
                        return false;
                    }
                }
            }
            if (m <= 0 || m > MAX_MEMORIA_KB || t <= 0 || t > MAX_ITERACIONES || p <= 0 || p > MAX_PARALELISMO) {
                return false;
            }
            byte[] sal = B64D.decode(partes[4]);
            byte[] esperado = B64D.decode(partes[5]);
            byte[] obtenido = calcular(password, sal, m, t, p, esperado.length);
            return MessageDigest.isEqual(esperado, obtenido);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Gasta el mismo tiempo que una verificacion real (para no revelar si el usuario existe). */
    void verificarFalso(String password) {
        verify(password, hashFalso);
    }

    private static byte[] calcular(String password, byte[] sal, int m, int t, int p) {
        return calcular(password, sal, m, t, p, HASH_BYTES);
    }

    private static byte[] calcular(String password, byte[] sal, int m, int t, int p, int largo) {
        Argon2Parameters parametros = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(t)
                .withMemoryAsKB(m)
                .withParallelism(p)
                .withSalt(sal)
                .build();
        Argon2BytesGenerator generador = new Argon2BytesGenerator();
        generador.init(parametros);
        byte[] salida = new byte[largo];
        generador.generateBytes(password.getBytes(StandardCharsets.UTF_8), salida);
        return salida;
    }
}
