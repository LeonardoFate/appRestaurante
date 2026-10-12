package com.restaurantes;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * Reglas de arquitectura (ADR-001 y ADR-014). Se ejecutan en el CI: un cambio que las
 * rompa no se integra.
 */
@AnalyzeClasses(packages = "com.restaurantes", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final List<String> MODULOS = List.of(
            "auth", "menu", "salon", "pedidos", "cocina", "delivery",
            "pagos", "facturacion", "sync", "reportes", "plataforma");

    /** Lo "internal" de un modulo solo lo usa ese mismo modulo. */
    static ArchRule internalEsPrivado(String modulo, String raiz) {
        return noClasses()
                .that().resideOutsideOfPackage(raiz + "." + modulo + "..")
                .should().dependOnClassesThat().resideInAPackage(raiz + "." + modulo + ".internal..")
                .allowEmptyShould(true)
                .as("lo internal del modulo '" + modulo + "' es privado");
    }

    @ArchTest
    static void loInternalDeUnModuloEsPrivado(JavaClasses clases) {
        MODULOS.forEach(m -> internalEsPrivado(m, "com.restaurantes").check(clases));
    }

    @ArchTest
    static final ArchRule sharedNoDependeDeLosModulos = noClasses()
            .that().resideInAPackage("com.restaurantes.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    MODULOS.stream().map(m -> "com.restaurantes." + m + "..").toArray(String[]::new))
            .allowEmptyShould(true);

    /** La API publica de un modulo no expone JDBC ni el pool: los detalles de acceso a datos no cruzan modulos. */
    @ArchTest
    static final ArchRule lasApisNoUsanJdbc = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "io.agroal..", "jakarta.ws.rs..")
            .allowEmptyShould(true);

    /**
     * El pool de conexiones solo lo usan {@code TenantTransaction} y las funciones de login (que corren antes de conocer
     * el tenant). Todo lo demas accede a la base mediante la unidad de trabajo del tenant.
     */
    @ArchTest
    static final ArchRule soloTenantTransactionYElLoginUsanElPool = noClasses()
            .that().resideOutsideOfPackages("com.restaurantes.shared.tenancy..", "com.restaurantes.auth.internal..")
            .should().dependOnClassesThat().resideInAPackage("io.agroal..")
            .allowEmptyShould(true);

    /** ADR-014: el dominio es Java puro; el framework y JDBC viven en los adaptadores. */
    @ArchTest
    static final ArchRule elDominioEsJavaPuro = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta..", "io.quarkus..", "org.jboss..", "java.sql..", "io.agroal..")
            .allowEmptyShould(true);
}
