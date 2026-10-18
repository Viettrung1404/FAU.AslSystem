package com.asl.backend;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.asl.backend");

    @Test
    void innerLayersStayIndependent() {
        classes().that().resideInAnyPackage("..domain..", "..application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.asl.backend..")
                .allowEmptyShould(true).check(production);
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                        "jakarta.servlet..", "..adapter..", "..configuration..")
                .allowEmptyShould(true).check(production);
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..application..")
                .allowEmptyShould(true).check(production);
    }

    @Test
    void modulesOnlyConsumePublishedApplicationApi() {
        classes().should(new ArchCondition<JavaClass>("only access another module through application.api") {
            @Override public void check(JavaClass item, ConditionEvents events) {
                String source = module(item);
                if (source.isEmpty() || item.getPackageName().contains(".configuration")) return;
                item.getDirectDependenciesFromSelf().forEach(dependency -> {
                    JavaClass target = dependency.getTargetClass();
                    String destination = module(target);
                    if (!destination.isEmpty() && !source.equals(destination)
                            && !(target.getPackageName().endsWith(".application.api")
                            || target.getPackageName().contains(".application.api."))) {
                        events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
                    }
                });
            }
        }).check(production);
        slices().matching("com.asl.backend.(*)..").should().beFreeOfCycles().check(production);
    }

    private static String module(JavaClass type) {
        String prefix = "com.asl.backend.";
        String name = type.getPackageName();
        return name.startsWith(prefix) ? name.substring(prefix.length()).split("\\.")[0] : "";
    }
}
