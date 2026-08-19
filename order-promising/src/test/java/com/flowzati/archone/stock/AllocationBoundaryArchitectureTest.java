package com.flowzati.archone.stock;

import static org.assertj.core.api.Assertions.assertThat;

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
 * allocation 與 ordering 之間的邊界。
 *
 * <p>配貨決策取得需求的唯一來源是 allocation-owned {@code allocation_demands} projection；
 * order source adapter 只讀 published adapter view。記錄結果的唯一方式是寫 allocation 的表並
 * 發事件。這支測試把那條邊界變成建置失敗，而不是只留在文件裡的約定。
 *
 * <p><b>為什麼不只檢查 import。</b>不 import 型別但在 SQL 字串裡寫表名，一樣是跨過邊界，
 * 而且更難發現——它不會出現在任何依賴圖上。
 */
@DisplayName("Allocation boundary architecture")
class AllocationBoundaryArchitectureTest {

  private static final Path STOCK_ROOT =
      Path.of("src/main/java/com/flowzati/archone/stock");
  private static final Path ALLOCATION_ROOT = STOCK_ROOT.resolve("allocation");
  private static final Path INVENTORY_ROOT = STOCK_ROOT.resolve("inventory");
  private static final Path MOVEMENT_ROOT = STOCK_ROOT.resolve("movement");
  private static final Path ORDERING_ROOT =
      Path.of("src/main/java/com/flowzati/archone/ordering");
  private static final Path ALLOCATION_APPLICATION_ROOT = ALLOCATION_ROOT.resolve("application");
  private static final Path INVENTORY_APPLICATION_ROOT = INVENTORY_ROOT.resolve("application");
  private static final Path ORDERING_APPLICATION_ROOT = ORDERING_ROOT.resolve("application");

  /** ordering 的訂單聚合根與它的 repository——allocation 兩者都不該認識。 */
  private static final Pattern ORDER_AGGREGATE_IMPORT = Pattern.compile(
      "import\\s+com\\.flowzati\\.archone\\.ordering\\.domain\\."
          + "(aggregate\\.Order|entity\\.OrderLine|type\\.OrderStatus"
          + "|repository\\.OrderRepository)\\s*;");

  /**
   * ordering 擁有的表名。
   *
   * <p>order adapter view 的 SQL 定義留在 migration；Java allocation code 不直接命名來源表。
   */
  private static final Pattern ORDERING_TABLE_NAME =
      Pattern.compile("\\b(orders|order_lines)\\b");

  /**
   * ordering 的行上，除了識別碼以外的欄位。
   *
   * <p><b>邊界的性質變了，所以規則跟著換，而不是為舊規則開例外。</b>搬運持有
   * {@code order_line_id}——那是需求與執行之間唯一的連結（對應 Odoo 的
   * {@code stock_move.sale_line_id}）。從「不知道對方存在」變成「持有對方的識別碼」之後，
   * 「不得出現 {@code order_lines}」這條規則已經擋不住真正該擋的東西了：持有 id 是允許的，
   * 但**沿著它去讀那條行的其他欄位不行**。
   *
   * <p>因此改成正面列出那些欄位。{@code sku_code}、{@code quantity} 不在此列——它們同時是
   * allocation 自己表上的欄位名，列進來只會擋到自己的 SQL。真正只屬於 ordering 的是這些。
   */
  private static final Pattern ORDERING_ONLY_COLUMN = Pattern.compile(
      "\\b(line_no|external_order_no|ship_to_zone|ship_to_address|promised_delivery_date"
          + "|cancelled_at|backordered_since|allocated_at|placed_at)\\b");

  /** allocation 擁有的表名，ordering 不該碰。 */
  private static final Pattern ALLOCATION_TABLE_NAME = Pattern.compile(
      "\\b(stock_pools|stock_locations|stock_pickings|stock_picking_types"
          + "|stock_moves|stock_move_lines|allocation_demands|allocation_demand_lines"
          + "|allocation_cancellation_operations)\\b");

  private static final List<Path> ALLOCATION_DECISION_CORE = List.of(
      ALLOCATION_ROOT.resolve("domain/aggregate/AllocationDemand.java"),
      ALLOCATION_ROOT.resolve("domain/entity/AllocationDemandLine.java"),
      ALLOCATION_ROOT.resolve("domain/valueobject/AllocationCandidateBatch.java"),
      ALLOCATION_ROOT.resolve("domain/service/AllocationFifoSelector.java"),
      ALLOCATION_ROOT.resolve("domain/service/AllocationDemandPlanner.java"),
      ALLOCATION_ROOT.resolve("domain/service/FefoBatchQueue.java"),
      ALLOCATION_ROOT.resolve("domain/valueobject/AllocationDemandPlan.java"),
      ALLOCATION_ROOT.resolve("domain/valueobject/AllocationBatchPick.java"));

  @Test
  @DisplayName("allocation 不得認識 ordering 的訂單聚合根——決策只讀 persisted demand")
  void allocationDoesNotImportTheOrderAggregate() {
    List<String> violations = sourcesUnder(STOCK_ROOT)
        .filter(source -> ORDER_AGGREGATE_IMPORT.matcher(readSource(source)).find())
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("allocation decision core 只使用 allocation-owned identity，不含 source aggregate 欄位")
  void allocationDecisionCoreUsesOnlyAllocationOwnedIdentity() {
    Pattern sourceSpecificIdentity = Pattern.compile(
        "\\b(orderId|orderLineId|transferId|replenishmentId|productionOrderId)\\b");
    Pattern sourceAggregateImport = Pattern.compile(
        "import\\s+com\\.flowzati\\.archone\\.(ordering|transfer|replenishment|production)\\.");

    List<String> violations = ALLOCATION_DECISION_CORE.stream()
        .filter(path -> {
          String source = stripComments(readSource(path));
          return sourceSpecificIdentity.matcher(source).find()
              || sourceAggregateImport.matcher(source).find()
              || source.contains("Repository");
        })
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("allocation 的程式碼不得出現 orders／order_lines——SQL 字串繞過型別也算")
  void allocationDoesNotNameOrderingTables() {
    List<String> violations = sourcesUnder(STOCK_ROOT)
        .filter(source -> ORDERING_TABLE_NAME.matcher(stripComments(readSource(source))).find())
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("allocation 可以持有 order_line_id，但不得讀那條行的其他欄位")
  void allocationHoldsTheOrderLineIdAndNothingElseFromThatLine() {
    List<String> violations = sourcesUnder(STOCK_ROOT)
        .filter(source -> ORDERING_ONLY_COLUMN.matcher(stripComments(readSource(source))).find())
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("order_line_id 這個例外必須真的被用到——沒有用到的例外只是裝飾")
  void theOrderLineIdExceptionIsActuallyExercised() {
    // 上一條允許了一件事，這一條確認那件事真的發生。少了它，有人日後把連結拿掉、改回用
    // 訂單 id 對應，上面那條規則仍然全綠——而邊界已經悄悄退回舊的形狀。
    List<String> holders = sourcesUnder(STOCK_ROOT)
        .filter(source -> stripComments(readSource(source)).contains("orderLineId"))
        .map(Path::toString)
        .toList();

    assertThat(holders).isNotEmpty();
  }

  @Test
  @DisplayName("ordering 的程式碼不得出現 allocation 的任何一張表——每張表只有一個 module 寫")
  void orderingDoesNotNameAllocationTables() {
    List<String> violations = sourcesUnder(ORDERING_ROOT)
        .filter(source -> ALLOCATION_TABLE_NAME.matcher(stripComments(readSource(source))).find())
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("allocation 不得持有任何寫入 ordering 的能力——沒有 OrderRepository 就沒有 save")
  void allocationHasNoWayToWriteOrders() {
    // 這一條與上面的 import 檢查重疊，但守的是不同的東西：即使有人在 allocation 裡自己定義
    // 一個介面去寫 orders，這裡也會抓到——因為它終究要寫出表名或 import 那個聚合根。
    //
    // 真正的保證其實在型別上：DemandRepository 沒有任何寫入方法，而它是 allocation 取得需求
    // 的唯一入口。這支測試守的是「不要繞過它」。
    List<String> violations = sourcesUnder(STOCK_ROOT)
        .filter(source -> {
          String code = stripComments(readSource(source));
          return code.contains("orderRepository") || code.contains("OrderRepository");
        })
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("掃描應真的看到檔案——路徑寫錯時這些規則不能無聲通過")
  void actuallyScansSomething() {
    assertThat(sourcesUnder(STOCK_ROOT)).hasSizeGreaterThan(20);
    assertThat(sourcesUnder(ORDERING_ROOT)).hasSizeGreaterThan(15);
  }

  @Test
  @DisplayName("stock 命令、喚醒結果與交易 usecase 不得依賴 Kafka 或 Temporal SDK")
  void allocationTransactionBoundariesAreTransportNeutral() {
    List<Path> boundaries = Stream.of(
        ALLOCATION_APPLICATION_ROOT.resolve("command/AllocateOrderCommand.java"),
        INVENTORY_APPLICATION_ROOT.resolve("command/ConfirmStockReceiptCommand.java"),
        ALLOCATION_APPLICATION_ROOT.resolve("command/AllocateWaitingDemandCommand.java"),
        ALLOCATION_APPLICATION_ROOT.resolve("usecase/AllocateOrderUsecase.java"),
        INVENTORY_APPLICATION_ROOT.resolve("usecase/ConfirmStockReceiptUsecase.java"),
        ALLOCATION_APPLICATION_ROOT.resolve("TransactionalAllocationAttempt.java"))
        .toList();

    List<String> violations = boundaries.stream()
        .filter(path -> {
          String source = stripComments(readSource(path));
          return source.contains("org.apache.kafka")
              || source.contains("io.temporal")
              || source.contains("IntegrationEvent");
        })
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("inbound application usecases 不得依賴 message envelope 或 Inbox repository")
  void inboundApplicationUsecasesDoNotOwnMessagingIdempotency() {
    List<Path> consumerUsecases = List.of(
        ALLOCATION_APPLICATION_ROOT.resolve("usecase/AllocateOrderUsecase.java"),
        ALLOCATION_APPLICATION_ROOT.resolve("TransactionalAllocationAttempt.java"),
        ALLOCATION_APPLICATION_ROOT.resolve("usecase/CancelMovementsUsecase.java"),
        INVENTORY_APPLICATION_ROOT.resolve("usecase/ConfirmStockReceiptUsecase.java"),
        ORDERING_APPLICATION_ROOT.resolve("usecase/RecordOrderAllocationUsecase.java"));

    List<String> violations = consumerUsecases.stream()
        .filter(path -> {
          String source = stripComments(readSource(path));
          return source.contains("com.flowzati.archone.messaging")
              || source.contains("InboundCommand")
              || source.contains("InboxRepo");
        })
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("stock 垂直模組依賴方向固定為 allocation → inventory → movement")
  void stockModulesFollowTheDeclaredDependencyDirection() {
    Pattern allocationImport = Pattern.compile(
        "import\\s+com\\.flowzati\\.archone\\.stock\\.allocation\\.");
    Pattern upstreamImport = Pattern.compile(
        "import\\s+com\\.flowzati\\.archone\\.stock\\.(allocation|inventory)\\.");

    assertThat(forbiddenImportsUnder(INVENTORY_ROOT, allocationImport))
        .as("inventory 不得反向依賴 allocation")
        .isEmpty();
    assertThat(forbiddenImportsUnder(MOVEMENT_ROOT, upstreamImport))
        .as("movement 不得反向依賴 allocation 或 inventory")
        .isEmpty();
  }

  /** 註解裡提到這些表名是為了解釋邊界，不該被當成違規。 */
  private static String stripComments(String source) {
    return source
        .replaceAll("(?s)/\\*.*?\\*/", "")
        .replaceAll("(?m)//.*$", "");
  }

  private static Stream<Path> sourcesUnder(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.filter(path -> path.toString().endsWith(".java")).toList().stream();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static List<String> forbiddenImportsUnder(Path root, Pattern forbiddenImport) {
    return sourcesUnder(root)
        .filter(source -> forbiddenImport.matcher(stripComments(readSource(source))).find())
        .map(Path::toString)
        .toList();
  }

  private static String readSource(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
