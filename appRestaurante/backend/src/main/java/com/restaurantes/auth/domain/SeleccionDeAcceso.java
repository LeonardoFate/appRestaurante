package com.restaurantes.auth.domain;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decide a que restaurante y sucursal entra un usuario autenticado y con que roles. Java puro y sin
 * acceso a datos: solo razona sobre las membresias activas que le dan.
 *
 * <p>Reglas:
 * <ul>
 *   <li>Sin membresias activas no hay acceso.</li>
 *   <li>Si el usuario pertenece a varios restaurantes, debe indicar a cual entra.</li>
 *   <li>Una membresia sin sucursal (todas) exige indicar la sucursal; una con sucursal fija la fija.</li>
 *   <li>Solo se puede entrar a un restaurante o sucursal donde se tiene una membresia.</li>
 * </ul>
 */
public final class SeleccionDeAcceso {

    private SeleccionDeAcceso() {
    }

    public static AccesoElegido elegir(List<Membresia> membresias, UUID tenantSolicitado, UUID sucursalSolicitada) {
        if (membresias == null || membresias.isEmpty()) {
            throw ReglaDeNegocioException.sinAcceso("SIN_ACCESO", "El usuario no tiene acceso a ningun restaurante");
        }

        UUID tenant = elegirTenant(membresias, tenantSolicitado);
        List<Membresia> delTenant = membresias.stream().filter(m -> m.tenantId().equals(tenant)).toList();

        if (sucursalSolicitada != null) {
            Set<String> roles = rolesQueAplican(delTenant, sucursalSolicitada);
            if (roles.isEmpty()) {
                throw ReglaDeNegocioException.sinAcceso("SIN_ACCESO", "El usuario no tiene acceso a esa sucursal");
            }
            // Si solo la cubre una membresia "todas las sucursales", hay que comprobar que la sucursal exista.
            boolean soloPorTodas = delTenant.stream().noneMatch(m -> sucursalSolicitada.equals(m.sucursalId()));
            return new AccesoElegido(tenant, sucursalSolicitada, soloPorTodas, roles);
        }

        boolean hayTodas = delTenant.stream().anyMatch(m -> m.sucursalId() == null);
        Set<UUID> fijas = delTenant.stream().map(Membresia::sucursalId).filter(s -> s != null).collect(Collectors.toSet());
        if (hayTodas || fijas.size() != 1) {
            throw ReglaDeNegocioException.invalido("SUCURSAL_REQUERIDA", "Indique la sucursal a la que entra");
        }
        UUID unica = fijas.iterator().next();
        return new AccesoElegido(tenant, unica, false, rolesQueAplican(delTenant, unica));
    }

    private static UUID elegirTenant(List<Membresia> membresias, UUID solicitado) {
        Set<UUID> tenants = membresias.stream().map(Membresia::tenantId).collect(Collectors.toSet());
        if (solicitado == null) {
            if (tenants.size() == 1) {
                return tenants.iterator().next();
            }
            throw ReglaDeNegocioException.invalido("TENANT_REQUERIDO", "Indique el restaurante al que entra");
        }
        if (!tenants.contains(solicitado)) {
            throw ReglaDeNegocioException.sinAcceso("SIN_ACCESO", "El usuario no tiene acceso a ese restaurante");
        }
        return solicitado;
    }

    private static Set<String> rolesQueAplican(List<Membresia> delTenant, UUID sucursal) {
        return delTenant.stream()
                .filter(m -> m.sucursalId() == null || m.sucursalId().equals(sucursal))
                .map(Membresia::rol)
                .collect(Collectors.toSet());
    }
}
