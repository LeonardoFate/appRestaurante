package com.restaurantes.flujo;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Perfil con varias conexiones: las pruebas de concurrencia necesitan transacciones realmente simultaneas
 * (el perfil normal usa UNA conexion para demostrar que el contexto de tenant no se filtra).
 */
public class PoolGrande implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.jdbc.min-size", "4",
                "quarkus.datasource.jdbc.max-size", "16");
    }
}
