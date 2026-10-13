package com.restaurantes.shared.events;

import com.restaurantes.shared.tenancy.TenantTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Manejadores de prueba: cada uno ejercita una conducta distinta del relay. */
final class ManejadoresDePrueba {

    private ManejadoresDePrueba() {
    }
}

/** Memoria compartida: cuantas veces ejecuto cada consumidor cada evento, y en que tenant. */
final class RegistroDeEventos {
    static final Map<String, AtomicInteger> LLAMADAS = new ConcurrentHashMap<>();
    static final Map<UUID, UUID> TENANT_DEL_EVENTO = new ConcurrentHashMap<>();

    private RegistroDeEventos() {
    }

    static int registrar(String consumidor, EventoDominio e) {
        TENANT_DEL_EVENTO.put(e.eventId(), e.tenantId());
        return LLAMADAS.computeIfAbsent(consumidor + ":" + e.eventId(), k -> new AtomicInteger()).incrementAndGet();
    }

    static int veces(String consumidor, UUID eventId) {
        AtomicInteger n = LLAMADAS.get(consumidor + ":" + eventId);
        return n == null ? 0 : n.get();
    }
}

/** Termina bien. */
@ApplicationScoped
class ManejadorOk implements ManejadorDeEvento {
    @Override
    public String consumidor() {
        return "prueba.ok";
    }

    @Override
    public String tipoEvento() {
        return "PruebaEvento";
    }

    @Override
    public void manejar(EventoDominio evento) {
        RegistroDeEventos.registrar(consumidor(), evento);
    }
}

/** Falla hasta el intento indicado en el payload ({@code exitoEnIntento}). */
@ApplicationScoped
class ManejadorFalla implements ManejadorDeEvento {
    @Override
    public String consumidor() {
        return "prueba.falla";
    }

    @Override
    public String tipoEvento() {
        return "PruebaFalla";
    }

    @Override
    public void manejar(EventoDominio evento) {
        int vez = RegistroDeEventos.registrar(consumidor(), evento);
        if (vez < evento.payload().get("exitoEnIntento").asInt()) {
            throw new IllegalStateException("fallo simulado " + vez);
        }
    }
}

/** Dos consumidores del mismo tipo: A siempre bien, B falla la primera vez. */
@ApplicationScoped
class ManejadorA implements ManejadorDeEvento {
    @Override
    public String consumidor() {
        return "prueba.a";
    }

    @Override
    public String tipoEvento() {
        return "PruebaDos";
    }

    @Override
    public void manejar(EventoDominio evento) {
        RegistroDeEventos.registrar(consumidor(), evento);
    }
}

@ApplicationScoped
class ManejadorB implements ManejadorDeEvento {
    @Override
    public String consumidor() {
        return "prueba.b";
    }

    @Override
    public String tipoEvento() {
        return "PruebaDos";
    }

    @Override
    public void manejar(EventoDominio evento) {
        if (RegistroDeEventos.registrar(consumidor(), evento) == 1) {
            throw new IllegalStateException("B falla la primera vez");
        }
    }
}

/** Escribe en la base y falla la primera vez: sirve para probar que se revierte el efecto y la marca de procesado. */
@ApplicationScoped
class ManejadorEfecto implements ManejadorDeEvento {
    @Inject
    TenantTransaction tx;

    @Override
    public String consumidor() {
        return "prueba.efecto";
    }

    @Override
    public String tipoEvento() {
        return "PruebaEfecto";
    }

    @Override
    public void manejar(EventoDominio evento) {
        int vez = RegistroDeEventos.registrar(consumidor(), evento);
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "insert into notificaciones(tenant_id, tipo, canal, destinatario, plantilla) values (?, 'prueba', 'email', 'a@b.com', 'p')")) {
            ps.setObject(1, tx.tenantActual());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        if (vez == 1) {
            throw new IllegalStateException("fallo despues de escribir");
        }
    }
}
