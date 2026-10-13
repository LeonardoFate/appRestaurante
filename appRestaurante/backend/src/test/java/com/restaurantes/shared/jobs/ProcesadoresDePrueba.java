package com.restaurantes.shared.jobs;

import com.restaurantes.shared.tenancy.TenantTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Procesadores de prueba: cada uno ejercita una conducta distinta del worker. */
final class ProcesadoresDePrueba {

    private ProcesadoresDePrueba() {
    }

    static void insertarNotificacion(TenantTransaction tx) {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "insert into notificaciones(tenant_id, tipo, canal, destinatario, plantilla) values (?, 'prueba', 'email', 'a@b.com', 'p')")) {
            ps.setObject(1, tx.tenantActual());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

/** Termina bien. */
@ApplicationScoped
class PruebaOk implements ProcesadorDeJob {
    @Override
    public String tipo() {
        return "prueba.ok";
    }

    @Override
    public void procesar(JobContexto ctx) {
        RegistroDeEjecuciones.registrar(ctx.jobId());
    }
}

/** Falla hasta el intento indicado en el payload ({@code exitoEnIntento}); con 0 no tiene exito nunca. */
@ApplicationScoped
class PruebaFalla implements ProcesadorDeJob {
    @Override
    public String tipo() {
        return "prueba.falla";
    }

    @Override
    public void procesar(JobContexto ctx) {
        RegistroDeEjecuciones.registrar(ctx.jobId());
        int exito = ctx.payload().get("exitoEnIntento").asInt();
        if (exito == 0 || ctx.intento() < exito) {
            throw new IllegalStateException("fallo simulado " + ctx.intento());
        }
    }
}

/** Fallo que no tiene sentido reintentar. */
@ApplicationScoped
class PruebaPermanente implements ProcesadorDeJob {
    @Override
    public String tipo() {
        return "prueba.permanente";
    }

    @Override
    public void procesar(JobContexto ctx) {
        RegistroDeEjecuciones.registrar(ctx.jobId());
        throw new JobPermanenteException("dato invalido simulado");
    }
}

/** Escribe algo en la base y, si el payload lo pide, falla despues: sirve para probar la atomicidad. */
@ApplicationScoped
class PruebaEfecto implements ProcesadorDeJob {
    @Inject
    TenantTransaction tx;

    @Override
    public String tipo() {
        return "prueba.efecto";
    }

    @Override
    public void procesar(JobContexto ctx) {
        RegistroDeEjecuciones.registrar(ctx.jobId());
        ProcesadoresDePrueba.insertarNotificacion(tx);
        if (ctx.payload().get("falla").asBoolean()) {
            throw new IllegalStateException("fallo despues de escribir");
        }
    }
}

/** Mientras procesa, "otro worker" toma el lease (en otra transaccion, ya confirmada) y luego este escribe un efecto. */
@ApplicationScoped
class PruebaRobo implements ProcesadorDeJob {
    @Inject
    TenantTransaction tx;

    @Inject
    AdminScheduler admin;

    @Override
    public String tipo() {
        return "prueba.robo";
    }

    @Override
    public void procesar(JobContexto ctx) {
        RegistroDeEjecuciones.registrar(ctx.jobId());
        admin.ejecutar("update jobs set bloqueado_por = 'otro-worker' where id = ?", ctx.jobId());
        ProcesadoresDePrueba.insertarNotificacion(tx);
    }
}

/** Registra en que tenant corre y cuantos jobs ve (con RLS solo deberia ver los de su restaurante). */
@ApplicationScoped
class PruebaTenant implements ProcesadorDeJob {
    @Inject
    TenantTransaction tx;

    @Override
    public String tipo() {
        return "prueba.tenant";
    }

    @Override
    public void procesar(JobContexto ctx) {
        RegistroDeEjecuciones.registrar(ctx.jobId());
        RegistroDeEjecuciones.TENANT_DE_LA_EJECUCION.put(ctx.jobId(), tx.tenantActual());
        Connection c = tx.connection();
        try (PreparedStatement ps = c.prepareStatement("select count(*) from jobs"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            RegistroDeEjecuciones.JOBS_VISIBLES_DURANTE_LA_EJECUCION.put(ctx.jobId(), rs.getLong(1));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
