package com.restaurantes;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.restaurantes.fixtures.intruso.pagos.internal.Intruso;
import com.restaurantes.fixtures.intruso.pedidos.internal.Secreto;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * Control de que las reglas de {@link ArchitectureTest} no son vacias: con clases de prueba
 * que SI las violan, la regla debe fallar. Si algun dia este test pasa en falso, las reglas
 * dejaron de proteger.
 */
class ArchitectureRulesControlTest {

    @Test
    void laReglaDeModulosDetectaUnaViolacion() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(Intruso.class, Secreto.class);
        assertThrows(AssertionError.class,
                () -> ArchitectureTest.internalEsPrivado("pedidos", "com.restaurantes.fixtures.intruso").check(fixtures));
    }
}
