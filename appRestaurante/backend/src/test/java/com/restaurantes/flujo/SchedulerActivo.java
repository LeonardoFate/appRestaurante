package com.restaurantes.flujo;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** Perfil con el planificador ENCENDIDO: el relay y el worker corren solos, como en produccion. */
public class SchedulerActivo implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.scheduler.enabled", "true",
                "app.relay.intervalo", "500ms",
                "app.jobs.intervalo", "500ms");
    }
}
