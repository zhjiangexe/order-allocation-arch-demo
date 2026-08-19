package com.flowzati.archone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * order-promising deployable 內各 bounded context 的編譯期邊界。
 *
 * <p>ArchUnit 檢查 class bytecode 中真的存在的型別依賴；source scan 另外攔截只為 JavaDoc
 * 留下的 import，避免無效 import 讓 package 看起來仍彼此耦合。
 */
@DisplayName("Order-promising context boundaries")
class ContextBoundaryArchitectureTest {

  private static final Path MAIN_ROOT = Path.of("src/main/java/com/flowzati/archone");
  private static final Path CATALOG_ROOT = MAIN_ROOT.resolve("catalog");
  private static final Path ORDERING_ROOT = MAIN_ROOT.resolve("ordering");
  private static final Path STOCK_ROOT = MAIN_ROOT.resolve("stock");

  private static final JavaClasses BUSINESS_CONTEXT_CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages(
          "com.flowzati.archone.catalog",
          "com.flowzati.archone.ordering",
          "com.flowzati.archone.stock");

  @Test
  @DisplayName("Ordering 與 Stock 只能透過 contracts 溝通，不得形成直接 class dependency")
  void orderingAndStockDoNotDependOnEachOther() {
    noClasses()
        .that().resideInAPackage("com.flowzati.archone.ordering..")
        .should().dependOnClassesThat().resideInAPackage("com.flowzati.archone.stock..")
        .check(BUSINESS_CONTEXT_CLASSES);

    noClasses()
        .that().resideInAPackage("com.flowzati.archone.stock..")
        .should().dependOnClassesThat().resideInAPackage("com.flowzati.archone.ordering..")
        .check(BUSINESS_CONTEXT_CLASSES);
  }

  @Test
  @DisplayName("Catalog 是被參照的主資料，不得反向依賴 Ordering 或 Stock")
  void catalogDoesNotDependOnTransactionalContexts() {
    noClasses()
        .that().resideInAPackage("com.flowzati.archone.catalog..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.flowzati.archone.ordering..",
            "com.flowzati.archone.stock..")
        .check(BUSINESS_CONTEXT_CLASSES);
  }

  @Test
  @DisplayName("source imports 也不得留下跨 context 的假依賴")
  void sourceImportsRespectContextBoundaries() {
    assertThat(forbiddenImportsUnder(
        ORDERING_ROOT,
        Pattern.compile("import\\s+com\\.flowzati\\.archone\\.(catalog|stock)\\.")))
        .as("Ordering 不得 import Catalog 或 Stock；跨 context 資料應使用自己的 snapshot 或 contract")
        .isEmpty();
    assertThat(forbiddenImportsUnder(
        STOCK_ROOT,
        Pattern.compile("import\\s+com\\.flowzati\\.archone\\.ordering\\.")))
        .as("Stock 不得 import Ordering；訂單來源只能經由 contract/read-model adapter")
        .isEmpty();
    assertThat(forbiddenImportsUnder(
        CATALOG_ROOT,
        Pattern.compile("import\\s+com\\.flowzati\\.archone\\.(ordering|stock)\\.")))
        .as("Catalog 不得反向 import transactional contexts")
        .isEmpty();
  }

  @Test
  @DisplayName("舊 promising support package 與 context-owned topic wrappers 不得回來")
  void legacySupportPackagesDoNotReturn() {
    assertThat(MAIN_ROOT.resolve("promising")).doesNotExist();
    assertThat(ORDERING_ROOT.resolve("application/event/OrderingEventTopics.java")).doesNotExist();
    assertThat(STOCK_ROOT.resolve(
        "allocation/application/event/PromisingEventTopics.java")).doesNotExist();
    assertThat(STOCK_ROOT.resolve(
        "inventory/application/event/InventoryEventTopics.java")).doesNotExist();

    List<String> legacyImports = javaSourcesUnder(MAIN_ROOT)
        .filter(source -> readSource(source).contains("com.flowzati.archone.promising."))
        .map(Path::toString)
        .toList();
    assertThat(legacyImports).isEmpty();
  }

  @Test
  @DisplayName("重構 package 時 module 與所有外部識別必須維持相容")
  void externalIdentifiersRemainStable() {
    assertThat(OrderingChannels.ORDER_EVENTS).isEqualTo("ordering.order-events");
    assertThat(AllocationChannels.ALLOCATION_EVENTS).isEqualTo("promising.allocation-events");
    assertThat(InventoryChannels.STOCK_EVENTS).isEqualTo("inventory.stock-events");
    assertThat(FulfillmentChannels.FULFILLMENT_HANDOFFS)
        .isEqualTo("promising.fulfillment-handoffs");

    assertThat(readSource(Path.of("build.gradle")))
        .contains("description = 'order-promising'");
    assertThat(readSource(Path.of("../settings.gradle")))
        .contains("include('order-promising')");
    assertThat(readSource(Path.of("src/main/resources/application.properties")))
        .contains("spring.application.name=order-promising")
        .contains("spring.kafka.consumer.group-id=order-promising-allocation");
    assertThat(readSource(Path.of("src/main/resources/application-dev.properties")))
        .contains("ORDER_PROMISING_DB_URL")
        .contains("ORDER_PROMISING_DB_USERNAME")
        .contains("ORDER_PROMISING_DB_PASSWORD")
        .contains("/order_promising");
  }

  private static List<String> forbiddenImportsUnder(Path root, Pattern pattern) {
    return javaSourcesUnder(root)
        .filter(source -> pattern.matcher(readSource(source)).find())
        .map(Path::toString)
        .toList();
  }

  private static Stream<Path> javaSourcesUnder(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.filter(path -> path.toString().endsWith(".java")).toList().stream();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static String readSource(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
