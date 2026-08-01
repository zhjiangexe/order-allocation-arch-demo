package com.flowzati.archone.allocation;

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
 * <p>配貨取得需求的唯一來源是 {@code demand_lines} view，記錄結果的唯一方式是寫自己的表並
 * 發事件。這支測試把那條邊界變成建置失敗，而不是一條寫在文件裡、下一次重構就被穿過去的約定。
 *
 * <p><b>為什麼不只檢查 import。</b>不 import 型別但在 SQL 字串裡寫表名，一樣是跨過邊界，
 * 而且更難發現——它不會出現在任何依賴圖上。
 */
@DisplayName("Allocation boundary architecture")
class AllocationBoundaryArchitectureTest {

  private static final Path ALLOCATION_ROOT =
      Path.of("src/main/java/com/flowzati/archone/allocation");
  private static final Path ORDERING_ROOT =
      Path.of("src/main/java/com/flowzati/archone/ordering");

  /** ordering 的訂單聚合根與它的 repository——allocation 兩者都不該認識。 */
  private static final Pattern ORDER_AGGREGATE_IMPORT = Pattern.compile(
      "import\\s+com\\.flowzati\\.archone\\.ordering\\.domain\\.(model\\.Order(Line|Status)?|repository\\.OrderRepository)\\s*;");

  /**
   * ordering 擁有的表名。
   *
   * <p>{@code demand_lines} 不在此列：那是 ordering 發布給 allocation 的介面，查它正是這個
   * 設計要的。也因此這條規則不需要為讀取開任何例外——allocation 沒有任何理由提到那兩張表。
   */
  private static final Pattern ORDERING_TABLE_NAME =
      Pattern.compile("\\b(orders|order_lines)\\b");

  /** allocation 擁有的表名，ordering 不該碰。 */
  private static final Pattern ALLOCATION_TABLE_NAME =
      Pattern.compile("\\b(stock_pools|stock_reservations|stock_locations)\\b");

  @Test
  @DisplayName("allocation 不得認識 ordering 的訂單聚合根——它看到的需求來自 demand_lines")
  void allocationDoesNotImportTheOrderAggregate() {
    List<String> violations = sourcesUnder(ALLOCATION_ROOT)
        .filter(source -> ORDER_AGGREGATE_IMPORT.matcher(readSource(source)).find())
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("allocation 的程式碼不得出現 orders／order_lines——SQL 字串繞過型別也算")
  void allocationDoesNotNameOrderingTables() {
    List<String> violations = sourcesUnder(ALLOCATION_ROOT)
        .filter(source -> ORDERING_TABLE_NAME.matcher(stripComments(readSource(source))).find())
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("ordering 的程式碼不得出現 stock_pools／stock_reservations——每張表只有一個 module 寫")
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
    List<String> violations = sourcesUnder(ALLOCATION_ROOT)
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
    assertThat(sourcesUnder(ALLOCATION_ROOT)).hasSizeGreaterThan(20);
    assertThat(sourcesUnder(ORDERING_ROOT)).hasSizeGreaterThan(15);
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

  private static String readSource(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
