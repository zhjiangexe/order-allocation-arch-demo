package com.flowzati.archone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.flowzati.archone.ArchoneApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class MonolithPackageArchitectureTest {

    private static final JavaClasses MONOLITH_CLASSES = new ClassFileImporter()
            .importUrl(ArchoneApplication.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation());

    @Test
    void onlyBootstrapMayDependOnBootstrap() {
        noClasses()
                .that()
                .resideOutsideOfPackage("com.flowzati.archone.bootstrap..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.bootstrap..")
                .check(MONOLITH_CLASSES);
    }

    @Test
    void productionPackagesDoNotDependOnDemo() {
        noClasses()
                .that()
                .resideOutsideOfPackage("com.flowzati.archone.demo..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.demo..")
                .check(MONOLITH_CLASSES);
    }
}
