package com.restaurantes.auth.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeleccionDeAccesoTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID SUC_1 = UUID.randomUUID();
    private static final UUID SUC_2 = UUID.randomUUID();

    private static String codigo(Runnable accion) {
        return assertThrows(ReglaDeNegocioException.class, accion::run).codigo();
    }

    @Test
    void sinMembresiasNoHayAcceso() {
        assertEquals("SIN_ACCESO", codigo(() -> SeleccionDeAcceso.elegir(List.of(), null, null)));
    }

    @Test
    void unaSolaMembresiaConSucursalFijaEntraSinIndicarNada() {
        AccesoElegido a = SeleccionDeAcceso.elegir(List.of(new Membresia(TENANT_A, SUC_1, "mesero")), null, null);
        assertEquals(TENANT_A, a.tenantId());
        assertEquals(SUC_1, a.sucursalId());
        assertEquals(Set.of("mesero"), a.roles());
        assertFalse(a.sucursalPorConfirmar(), "la sucursal viene de la membresia: ya esta garantizada por la base");
    }

    @Test
    void variosRestaurantesExigenElegir() {
        List<Membresia> ms = List.of(new Membresia(TENANT_A, SUC_1, "mesero"), new Membresia(TENANT_B, SUC_2, "cajero"));
        assertEquals("TENANT_REQUERIDO", codigo(() -> SeleccionDeAcceso.elegir(ms, null, null)));

        AccesoElegido b = SeleccionDeAcceso.elegir(ms, TENANT_B, null);
        assertEquals(TENANT_B, b.tenantId());
        assertEquals(Set.of("cajero"), b.roles());
    }

    @Test
    void noSePuedeEntrarAUnRestauranteAjeno() {
        List<Membresia> ms = List.of(new Membresia(TENANT_A, SUC_1, "mesero"));
        assertEquals("SIN_ACCESO", codigo(() -> SeleccionDeAcceso.elegir(ms, TENANT_B, null)));
    }

    @Test
    void unaMembresiaDeTodasLasSucursalesExigeIndicarLaSucursal() {
        List<Membresia> ms = List.of(new Membresia(TENANT_A, null, "admin"));
        assertEquals("SUCURSAL_REQUERIDA", codigo(() -> SeleccionDeAcceso.elegir(ms, null, null)));

        AccesoElegido a = SeleccionDeAcceso.elegir(ms, null, SUC_2);
        assertEquals(SUC_2, a.sucursalId());
        assertTrue(a.sucursalPorConfirmar(), "hay que comprobar que la sucursal exista en el restaurante");
    }

    @Test
    void conVariasSucursalesFijasHayQueElegir() {
        List<Membresia> ms = List.of(new Membresia(TENANT_A, SUC_1, "mesero"), new Membresia(TENANT_A, SUC_2, "mesero"));
        assertEquals("SUCURSAL_REQUERIDA", codigo(() -> SeleccionDeAcceso.elegir(ms, null, null)));
        assertEquals(SUC_2, SeleccionDeAcceso.elegir(ms, null, SUC_2).sucursalId());
    }

    @Test
    void noSePuedeEntrarAUnaSucursalDondeNoSeTieneMembresia() {
        List<Membresia> ms = List.of(new Membresia(TENANT_A, SUC_1, "mesero"));
        assertEquals("SIN_ACCESO", codigo(() -> SeleccionDeAcceso.elegir(ms, null, SUC_2)));
    }

    @Test
    void losRolesSonLaUnionDeLasMembresiasQueAplican() {
        List<Membresia> ms = List.of(
                new Membresia(TENANT_A, SUC_1, "cajero"),
                new Membresia(TENANT_A, null, "mesero"),          // todas las sucursales
                new Membresia(TENANT_A, SUC_2, "cocina"),         // otra sucursal: no aplica a SUC_1
                new Membresia(TENANT_B, SUC_1, "owner"));          // otro restaurante: no aplica

        AccesoElegido a = SeleccionDeAcceso.elegir(ms, TENANT_A, SUC_1);

        assertEquals(Set.of("cajero", "mesero"), a.roles());
    }
}
