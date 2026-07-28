package com.flowzati.archone.ordering;

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
 * 訂單行的存取規則。
 *
 * <p>收單目前只收一行，因此「取第一行」在今天完全正確、不會有任何測試失敗，卻會在放寬多行
 * 時安靜地只處理第一行。這支測試把那種寫法變成建置失敗。
 *
 * <p>要摺成單一值的地方一律走 {@code Order.requireSingleSku()} 或
 * {@code LineSnapshot.requireSingleSku()}——它們以集合運算取值並在假設破裂時拋錯，而不是
 * 以位置存取，所以這條規則不需要為它們開例外。
 */
@DisplayName("Ordering architecture")
class OrderingArchitectureTest {

  private static final List<Path> PRODUCTION_ROOTS = List.of(
      Path.of("src/main/java/com/flowzati/archone/ordering"),
      Path.of("src/main/java/com/flowzati/archone/allocation"),
      Path.of("src/main/java/com/flowzati/archone/demo"),
      Path.of("src/main/java/com/flowzati/archone/bootstrap")
  );

  /**
   * 對「行的集合」做位置存取：{@code getLines().get(0)}、{@code lines().getFirst()}、
   * {@code getLines().stream().findFirst()} 等。
   *
   * <p>刻意鎖定接在 {@code getLines()}／{@code lines()} 之後的呼叫，而不是全面禁止
   * {@code getFirst()}——後者會誤傷任何其他集合，而規則一旦開始誤傷就會被加上例外，例外
   * 一多就沒人記得規則本來要擋什麼。
   */
  private static final Pattern POSITIONAL_LINE_ACCESS = Pattern.compile(
      "(getLines\\(\\)|\\blines\\(\\))\\s*\\.\\s*(get\\(|getFirst\\(|stream\\(\\)\\s*\\.\\s*findFirst\\()");

  @Test
  @DisplayName("production code 不得以位置存取訂單行——那在單行下正確，多行下安靜出錯")
  void forbidsPositionalAccessToOrderLines() {
    List<String> violations = javaSources()
        .filter(source -> POSITIONAL_LINE_ACCESS.matcher(stripComments(readSource(source)))
            .find())
        .map(Path::toString)
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("掃描應真的看到檔案——路徑寫錯時這支測試不能無聲通過")
  void actuallyScansSomething() {
    assertThat(javaSources()).hasSizeGreaterThan(30);
  }

  /** 註解裡提到這些寫法是為了解釋為什麼禁止它們，不該被當成違規。 */
  private static String stripComments(String source) {
    return source
        .replaceAll("(?s)/\\*.*?\\*/", "")
        .replaceAll("(?m)//.*$", "");
  }

  private static Stream<Path> javaSources() {
    return PRODUCTION_ROOTS.stream().flatMap(root -> {
      try (Stream<Path> paths = Files.walk(root)) {
        return paths.filter(path -> path.toString().endsWith(".java")).toList().stream();
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
  }

  private static String readSource(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
