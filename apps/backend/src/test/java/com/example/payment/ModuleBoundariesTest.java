package com.example.payment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Enforces the module rules in ADR-001, because a monolith has no network boundary to do it. */
class ModuleBoundariesTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.example.payment");

  @Test
  void modulesHaveNoCycles() {
    slices().matching("com.example.payment.(*)..").should().beFreeOfCycles().check(classes);
  }

  @Test
  void commonDependsOnNoOtherModule() {
    noClasses()
        .that()
        .resideInAPackage("com.example.payment.common..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.example.payment.config..",
            "com.example.payment.security..",
            "com.example.payment.merchant..",
            "com.example.payment.checkout..",
            "com.example.payment.payment..",
            "com.example.payment.provider..",
            "com.example.payment.webhook..")
        .check(classes);
  }

  @Test
  void paymentRulesDoNotDependOnWebhooks() {
    noClasses()
        .that()
        .resideInAPackage("com.example.payment.payment..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.example.payment.webhook..")
        .check(classes);
  }

  @Test
  void providersKnowNothingAboutPaymentRules() {
    noClasses()
        .that()
        .resideInAPackage("com.example.payment.provider..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.example.payment.payment..", "com.example.payment.checkout..")
        .check(classes);
  }

  @Test
  void entitiesStayInsideTheirModule() {
    noClasses()
        .that()
        .resideOutsideOfPackage("com.example.payment.checkout..")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("CheckoutSession")
        .check(classes);
  }
}
