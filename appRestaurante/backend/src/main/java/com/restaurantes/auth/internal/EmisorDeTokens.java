package com.restaurantes.auth.internal;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Emite los tokens: el JWT de acceso (corto, firmado) y el refresh token (opaco, de un solo uso). */
@ApplicationScoped
class EmisorDeTokens {

    /** Refresh token recien creado: el valor se entrega al cliente y solo el hash se guarda. */
    record RefreshNuevo(String token, String hash) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    @ConfigProperty(name = "app.auth.access-minutos")
    int accesoMinutos;

    long accessSegundos() {
        return Duration.ofMinutes(accesoMinutos).toSeconds();
    }

    /** JWT de acceso con los claims que consume {@code CallerContext}. */
    String access(UUID usuarioId, UUID tenantId, UUID sucursalId, Set<String> roles) {
        return Jwt.issuer("restaurantes")
                .subject(usuarioId.toString())
                .groups(roles)
                .claim("tenant_id", tenantId.toString())
                .claim("sucursal_id", sucursalId.toString())
                .expiresIn(Duration.ofMinutes(accesoMinutos))
                .sign();
    }

    /**
     * Refresh token: {@code <tenant>.<32 bytes aleatorios>}. El prefijo permite fijar el contexto del tenant antes de
     * consultar la tabla (que tiene RLS); el secreto aleatorio es lo que lo hace imposible de adivinar.
     */
    RefreshNuevo nuevoRefresh(UUID tenantId) {
        byte[] secreto = new byte[32];
        RANDOM.nextBytes(secreto);
        String token = tenantId + "." + B64URL.encodeToString(secreto);
        return new RefreshNuevo(token, hash(token));
    }

    /** Tenant codificado en el refresh token. Un formato invalido es simplemente "no autenticado". */
    UUID tenantDe(String refreshToken) {
        int punto = refreshToken == null ? -1 : refreshToken.indexOf('.');
        if (punto <= 0 || punto == refreshToken.length() - 1) {
            throw tokenInvalido();
        }
        try {
            return UUID.fromString(refreshToken.substring(0, punto));
        } catch (IllegalArgumentException e) {
            throw tokenInvalido();
        }
    }

    /** SHA-256 en hexadecimal: el token tiene 256 bits de entropia, no hace falta un hash lento. */
    String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    static ReglaDeNegocioException tokenInvalido() {
        return ReglaDeNegocioException.noAutenticado("TOKEN_INVALIDO", "La sesion no es valida; inicie sesion de nuevo");
    }
}
