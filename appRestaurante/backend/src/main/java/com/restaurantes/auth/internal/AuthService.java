package com.restaurantes.auth.internal;

import com.restaurantes.auth.domain.AccesoElegido;
import com.restaurantes.auth.domain.SeleccionDeAcceso;
import com.restaurantes.shared.errors.ReglaDeNegocioException;
import com.restaurantes.shared.ids.UuidV7;
import com.restaurantes.shared.tenancy.TenantTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Inicio, renovacion y cierre de sesion.
 *
 * <p>Principios: (1) un login fallido NUNCA revela si el usuario existe (misma respuesta y mismo coste);
 * (2) el refresh token es de un solo uso: usarlo dos veces revoca toda la sesion (alguien lo copio);
 * (3) las contrasenas y los tokens jamas se escriben en los logs.
 */
@ApplicationScoped
class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

    private sealed interface ResultadoRefresh permits Rotado, Rechazado {
    }

    private record Rotado(SesionResponse sesion) implements ResultadoRefresh {
    }

    private record Rechazado(String motivo) implements ResultadoRefresh {
    }

    @Inject
    UsuariosLoginRepository usuarios;

    @Inject
    RefreshTokenRepository refresh;

    @Inject
    EmisorDeTokens emisor;

    @Inject
    PasswordHasher hasher;

    @Inject
    TenantTransaction tx;

    @ConfigProperty(name = "app.auth.max-intentos")
    int maxIntentos;

    @ConfigProperty(name = "app.auth.bloqueo-minutos")
    int bloqueoMinutos;

    @ConfigProperty(name = "app.auth.refresh-dias")
    int refreshDias;

    // ------------------------------------------------------------------ login

    SesionResponse login(String email, String password, UUID tenantSolicitado, UUID sucursalSolicitada) {
        Optional<UsuarioLogin> encontrado = usuarios.buscarLogin(email.trim().toLowerCase());
        if (encontrado.isEmpty()) {
            hasher.verificarFalso(password);
            throw credencialesInvalidas();
        }
        UsuarioLogin usuario = encontrado.get();
        if (!usuario.activo() || usuario.bloqueado()) {
            // Misma respuesta y mismo coste que una clave incorrecta; no se cuenta como intento.
            hasher.verificarFalso(password);
            throw credencialesInvalidas();
        }
        if (!hasher.verify(password, usuario.passwordHash())) {
            usuarios.registrarFallido(usuario.id(), maxIntentos, bloqueoMinutos);
            throw credencialesInvalidas();
        }
        usuarios.registrarExitoso(usuario.id());

        AccesoElegido acceso = SeleccionDeAcceso.elegir(usuarios.membresias(usuario.id()), tenantSolicitado, sucursalSolicitada);
        EmisorDeTokens.RefreshNuevo nuevoRefresh = tx.withTenant(acceso.tenantId(), c -> {
            if (!refresh.tenantActivo()) {
                throw ReglaDeNegocioException.sinAcceso("RESTAURANTE_INACTIVO", "El restaurante no esta activo");
            }
            if (acceso.sucursalPorConfirmar() && !refresh.sucursalActiva(acceso.sucursalId())) {
                throw ReglaDeNegocioException.sinAcceso("SIN_ACCESO", "El usuario no tiene acceso a esa sucursal");
            }
            EmisorDeTokens.RefreshNuevo r = emisor.nuevoRefresh(acceso.tenantId());
            refresh.insertar(UuidV7.next(), usuario.id(), acceso.sucursalId(), r.hash(), refreshDias);
            return r;
        });
        return sesion(usuario.id(), acceso, nuevoRefresh.token());
    }

    // ---------------------------------------------------------------- refresh

    SesionResponse renovar(String refreshToken) {
        UUID tenantId = emisor.tenantDe(refreshToken);
        String hash = emisor.hash(refreshToken);
        // La revocacion por reutilizacion debe CONFIRMARSE: por eso el rechazo se devuelve como valor y la excepcion
        // se lanza despues del commit (lanzarla dentro de la transaccion desharia la revocacion).
        ResultadoRefresh resultado = tx.withTenant(tenantId, c -> rotar(tenantId, hash));
        if (resultado instanceof Rotado rotado) {
            return rotado.sesion();
        }
        LOG.debugf("Refresh rechazado: %s", ((Rechazado) resultado).motivo());
        throw EmisorDeTokens.tokenInvalido();
    }

    private ResultadoRefresh rotar(UUID tenantId, String hash) {
        Optional<RefreshTokenRepository.Fila> encontrada = refresh.buscarParaRotar(hash);
        if (encontrada.isEmpty()) {
            return new Rechazado("desconocido");
        }
        RefreshTokenRepository.Fila fila = encontrada.get();
        if (fila.revocado()) {
            return new Rechazado("revocado");
        }
        if (fila.usado()) {
            // Un token ya usado reaparece: lo mas probable es que se copio. Se cierra TODA la sesion.
            int revocados = refresh.revocarFamilia(fila.familiaId());
            LOG.warnf("Reutilizacion de refresh token: tenant=%s usuario=%s familia=%s (%d tokens revocados)",
                    tenantId, fila.usuarioId(), fila.familiaId(), revocados);
            return new Rechazado("reutilizado");
        }
        if (fila.expirado()) {
            refresh.revocarFamilia(fila.familiaId());
            return new Rechazado("expirado");
        }
        if (!refresh.tenantActivo() || !refresh.usuarioActivo(fila.usuarioId())) {
            refresh.revocarFamilia(fila.familiaId());
            return new Rechazado("cuenta inactiva");
        }
        AccesoElegido acceso;
        try {
            acceso = SeleccionDeAcceso.elegir(refresh.membresiasActivas(fila.usuarioId()), tenantId, fila.sucursalId());
        } catch (ReglaDeNegocioException e) {
            refresh.revocarFamilia(fila.familiaId());
            return new Rechazado("sin membresia");
        }
        if (acceso.sucursalPorConfirmar() && !refresh.sucursalActiva(acceso.sucursalId())) {
            refresh.revocarFamilia(fila.familiaId());
            return new Rechazado("sucursal inactiva");
        }

        refresh.marcarUsado(fila.id());
        EmisorDeTokens.RefreshNuevo nuevo = emisor.nuevoRefresh(tenantId);
        refresh.insertar(fila.familiaId(), fila.usuarioId(), fila.sucursalId(), nuevo.hash(), refreshDias);
        return new Rotado(sesion(fila.usuarioId(), acceso, nuevo.token()));
    }

    // ----------------------------------------------------------------- logout

    /** Cierra la sesion completa. Idempotente: un token desconocido o ya cerrado no es un error. */
    void cerrarSesion(String refreshToken) {
        UUID tenantId;
        try {
            tenantId = emisor.tenantDe(refreshToken);
        } catch (ReglaDeNegocioException e) {
            return;
        }
        String hash = emisor.hash(refreshToken);
        tx.runInTenant(tenantId, c -> refresh.buscarParaRotar(hash).ifPresent(f -> refresh.revocarFamilia(f.familiaId())));
    }

    // ---------------------------------------------------------------- helpers

    private SesionResponse sesion(UUID usuarioId, AccesoElegido acceso, String refreshToken) {
        String access = emisor.access(usuarioId, acceso.tenantId(), acceso.sucursalId(), acceso.roles());
        return new SesionResponse(access, "Bearer", emisor.accessSegundos(), refreshToken,
                acceso.tenantId(), acceso.sucursalId(), acceso.roles());
    }

    private static ReglaDeNegocioException credencialesInvalidas() {
        return ReglaDeNegocioException.noAutenticado("CREDENCIALES_INVALIDAS", "Correo o contrasena incorrectos");
    }
}
