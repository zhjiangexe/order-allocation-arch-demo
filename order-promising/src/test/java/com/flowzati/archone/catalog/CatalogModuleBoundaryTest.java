package com.flowzati.archone.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 主檔的依賴方向：{@code catalog} 不得依賴 {@code ordering} 或 {@code allocation}，
 * 反向則可以。
 *
 * <p>這條界線現在看起來是多餘的——本 change 裡 catalog 沒有任何理由 import 那兩個
 * package。它存在是為了 R3：屆時 allocation 的 {@code requireMatchingOwner()} 會需要讀
 * 貨主，若主檔那時已經反過來依賴 ordering，就會形成循環，而 R4 的驗收條件之一正是
 * allocation 不得 import {@code ordering.domain.model}。
 *
 * <p>以掃描原始碼而非 reflection 實作：import 在編譯後會被抹平成完整類別名，掃 bytecode
 * 抓不到「這個檔宣告了什麼依賴」的意圖，而掃原始碼可以，且同一手法 R4 能直接重用。
 */
@DisplayName("Catalog module boundary")
class CatalogModuleBoundaryTest {

  private static final Path CATALOG_SOURCE_ROOT =
      Path.of("src/main/java/com/flowzati/archone/catalog");

  private static final List<String> FORBIDDEN_IMPORTS = List.of(
      "import com.flowzati.archone.ordering",
      "import com.flowzati.archone.allocation"
  );

  /**
   * 主檔只提供查詢：`Owner`／`Product`／`Sku` 由 seed 建立，維護介面是另一件事，有自己的
   * 授權與稽核需求。提供半套版本會招來對它的依賴。
   *
   * <p>repository 的 {@code save} 不在此列——seed 需要它，而它不是對外表面。
   */
  private static final List<String> WRITE_VERBS =
      List.of("Create", "Update", "Delete", "Register", "Amend", "Remove");

  private static final List<String> WRITE_MAPPINGS =
      List.of("@PostMapping", "@PutMapping", "@PatchMapping", "@DeleteMapping");

  @Test
  @DisplayName("catalog 不應 import ordering 或 allocation")
  void doesNotDependOnOrderingOrAllocation() {
    List<String> violations = javaSources()
        .flatMap(source -> FORBIDDEN_IMPORTS.stream()
            .filter(forbidden -> readSource(source).contains(forbidden))
            .map(forbidden -> "%s → %s".formatted(source, forbidden)))
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("掃描應真的看到檔案——路徑寫錯時這支測試不能無聲通過")
  void actuallyScansSomething() {
    assertThat(javaSources()).isNotEmpty();
  }

  @Test
  @DisplayName("catalog 不應有寫入型的 usecase——主檔由 seed 建立")
  void exposesNoWriteUsecase() {
    List<String> writeUsecases = javaSources()
        .filter(path -> path.toString().contains("/application/usecase/"))
        .map(path -> path.getFileName().toString())
        .filter(name -> WRITE_VERBS.stream().anyMatch(name::startsWith))
        .toList();

    assertThat(writeUsecases).isEmpty();
  }

  @Test
  @DisplayName("catalog 的 REST 表面不應有寫入型 mapping")
  void exposesNoWriteEndpoint() {
    List<String> violations = javaSources()
        .filter(path -> path.toString().contains("/entrypoint/rest/"))
        .flatMap(source -> WRITE_MAPPINGS.stream()
            .filter(mapping -> readSource(source).contains(mapping))
            .map(mapping -> "%s → %s".formatted(source, mapping)))
        .toList();

    assertThat(violations).isEmpty();
  }

  private static Stream<Path> javaSources() {
    try (Stream<Path> paths = Files.walk(CATALOG_SOURCE_ROOT)) {
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
