package com.flowzati.archone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes;
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
 * bootstrap deployable 與它組裝的 bounded context 之間的編譯期邊界。
 *
 * <p>ArchUnit 檢查 class bytecode 中真的存在的型別依賴；source scan 另外攔截只為 JavaDoc
 * 留下的 import，避免無效 import 讓 package 看起來仍彼此耦合。
 */
@DisplayName("Bootstrap context boundaries")
class ContextBoundaryArchitectureTest {

    private static final Path MAIN_ROOT = Path.of("src/main/java/com/flowzati/archone");
    private static final Path LOGISTICS_DATA_ROOT =
            Path.of("../logistics-data-context/src/main/java/com/flowzati/archone/logisticsdata");
    private static final Path ORDERING_ROOT =
            Path.of("../ordering-context/src/main/java/com/flowzati/archone/ordering");
    private static final Path INVENTORY_ROOT =
            Path.of("../inventory-context/src/main/java/com/flowzati/archone/inventory");
    private static final Path WAREHOUSE_ROOT = INVENTORY_ROOT.resolve("warehouse");

    private static final JavaClasses BUSINESS_CONTEXT_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(
                    "com.flowzati.archone.logisticsdata",
                    "com.flowzati.archone.ordering",
                    "com.flowzati.archone.inventory");

    @Test
    @DisplayName("Ordering 與 Inventory 只能透過 contracts 溝通，不得形成直接 class dependency")
    void orderingAndInventoryDoNotDependOnEachOther() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.ordering..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.inventory..")
                .check(BUSINESS_CONTEXT_CLASSES);

        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.inventory..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.ordering..")
                .check(BUSINESS_CONTEXT_CLASSES);
    }

    @Test
    @DisplayName("Logistics Data 與 Inventory 不共享 domain model，也不得依賴 Ordering")
    void logisticsDataAndInventoryDoNotShareDomainClasses() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.logisticsdata..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.flowzati.archone.ordering..", "com.flowzati.archone.inventory..")
                .check(BUSINESS_CONTEXT_CLASSES);

        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.inventory..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.logisticsdata..")
                .check(BUSINESS_CONTEXT_CLASSES);
    }

    @Test
    @DisplayName("source imports 也不得留下跨 context 的假依賴")
    void sourceImportsRespectContextBoundaries() {
        assertThat(forbiddenImportsUnder(
                        ORDERING_ROOT,
                        Pattern.compile("import\\s+com\\.flowzati\\.archone\\.(logisticsdata|inventory)\\.")))
                .as("Ordering 不得 import Logistics Data 或 Inventory；跨 context 資料應使用自己的 snapshot 或 contract")
                .isEmpty();
        assertThat(forbiddenImportsUnder(
                        INVENTORY_ROOT,
                        Pattern.compile("import\\s+com\\.flowzati\\.archone\\.(logisticsdata|ordering)\\.")))
                .as("Inventory 不得 import Logistics Data 或 Ordering；跨 context 資料只能使用 identity、contract 或 adapter")
                .isEmpty();
        assertThat(forbiddenImportsUnder(
                        LOGISTICS_DATA_ROOT,
                        Pattern.compile("import\\s+com\\.flowzati\\.archone\\.(ordering|inventory)\\.")))
                .as("Logistics Data 不得反向 import transactional contexts")
                .isEmpty();
    }

    @Test
    @DisplayName("StockLocation 與 PickingType 由 Inventory warehouse capability 擁有")
    void warehouseOperationConfigurationBelongsToInventory() {
        assertThat(WAREHOUSE_ROOT.resolve("domain/aggregate/StockLocation.java"))
                .isRegularFile();
        assertThat(WAREHOUSE_ROOT.resolve("domain/aggregate/PickingType.java")).isRegularFile();
        assertThat(LOGISTICS_DATA_ROOT.resolve("domain/aggregate/StockLocation.java"))
                .doesNotExist();
        assertThat(LOGISTICS_DATA_ROOT.resolve("domain/aggregate/PickingType.java"))
                .doesNotExist();
    }

    @Test
    @DisplayName("舊 promising／stock package 與 context-owned topic wrappers 不得回來")
    void legacySupportPackagesDoNotReturn() {
        assertThat(MAIN_ROOT.resolve("promising")).doesNotExist();
        assertThat(MAIN_ROOT.resolve("stock")).doesNotExist();
        assertThat(ORDERING_ROOT.resolve("application/event/OrderingEventTopics.java"))
                .doesNotExist();
        assertThat(INVENTORY_ROOT.resolve("reservation/application/event/PromisingEventTopics.java"))
                .doesNotExist();
        assertThat(INVENTORY_ROOT.resolve("balance/application/event/InventoryEventTopics.java"))
                .doesNotExist();

        Pattern legacyPackage =
                Pattern.compile("com\\.flowzati\\.archone\\.(promising|stock\\.(allocation|inventory|movement))\\.");
        List<String> legacyImports = Stream.of(LOGISTICS_DATA_ROOT, ORDERING_ROOT, INVENTORY_ROOT)
                .flatMap(ContextBoundaryArchitectureTest::javaSourcesUnder)
                .filter(source -> legacyPackage.matcher(readSource(source)).find())
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
        assertThat(InventoryAggregateTypes.STOCK_POOL).isEqualTo("StockPool");
        assertThat(FulfillmentChannels.FULFILLMENT_HANDOFFS).isEqualTo("promising.fulfillment-handoffs");

        assertThat(readSource(Path.of("build.gradle"))).contains("description = 'application bootstrap'");
        assertThat(readSource(Path.of("build.gradle")))
                .contains("implementation project(':ordering-context')")
                .contains("implementation project(':inventory-context')")
                .contains("implementation project(':logistics-data-context')")
                .doesNotContain("platform-infrastructure");
        assertThat(readSource(Path.of("../ordering-context/build.gradle")))
                .contains("description = 'ordering bounded context'")
                .doesNotContain("project(':bootstrap')");
        assertThat(readSource(Path.of("../inventory-context/build.gradle")))
                .contains("description = 'inventory bounded context'")
                .doesNotContain("project(':bootstrap')");
        assertThat(readSource(Path.of("../logistics-data-context/build.gradle")))
                .contains("description = 'logistics data bounded context'")
                .doesNotContain("project(':bootstrap')");
        assertThat(readSource(Path.of("../settings.gradle")))
                .contains("include('bootstrap')")
                .contains("include('ordering-context')")
                .contains("include('inventory-context')")
                .contains("include('logistics-data-context')")
                .doesNotContain("include('platform-infrastructure')");
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
