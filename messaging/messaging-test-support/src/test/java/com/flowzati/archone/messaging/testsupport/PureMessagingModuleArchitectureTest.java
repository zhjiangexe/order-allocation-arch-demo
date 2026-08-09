package com.flowzati.archone.messaging.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PureMessagingModuleArchitectureTest {

  private static final List<String> PURE_MODULES = List.of(
      "messaging-api",
      "messaging-events",
      "messaging-producer-common",
      "messaging-consumer-common",
      "messaging-jdbc-common",
      "messaging-producer-jdbc",
      "messaging-consumer-kafka",
      "messaging-test-support");
  private static final List<String> FORBIDDEN_IMPORTS = List.of(
      "import org.springframework.",
      "import jakarta.persistence.",
      "import javax.persistence.");

  @Test
  void pureProductionSourcesDoNotImportSpringOrJpa() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : PURE_MODULES) {
      Path sourceRoot = root().resolve("messaging").resolve(module).resolve("src/main/java");
      if (!Files.exists(sourceRoot)) {
        continue;
      }
      try (var sources = Files.walk(sourceRoot)) {
        for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
          String content = Files.readString(source);
          FORBIDDEN_IMPORTS.stream()
              .filter(content::contains)
              .map(forbidden -> module + ": " + source.getFileName() + " -> " + forbidden)
              .forEach(violations::add);
        }
      }
    }

    assertThat(violations).as("pure messaging source boundary violations").isEmpty();
  }

  @Test
  void kafkaMapperModuleDependsOnlyOnGenericApiAndKafkaClient() throws IOException {
    Path module = root().resolve("messaging/messaging-consumer-kafka");
    String build = Files.readString(module.resolve("build.gradle"));
    String productionSources = readProductionSources(module.resolve("src/main/java"));

    assertThat(build)
        .contains("project(':messaging:messaging-api')")
        .contains("libs.kafka.clients")
        .doesNotContain("messaging-events")
        .doesNotContain("messaging-consumer-common")
        .doesNotContain("starter.kafka");
    assertThat(productionSources)
        .doesNotContain("com.flowzati.archone.messaging.events")
        .doesNotContain("org.springframework.kafka")
        .doesNotContain("@KafkaListener");
  }

  @Test
  void commonOrchestrationDoesNotContainPersistenceModels() throws IOException {
    String producer = readProductionSources(root().resolve(
        "messaging/messaging-producer-common/src/main/java"));
    String consumer = readProductionSources(root().resolve(
        "messaging/messaging-consumer-common/src/main/java"));

    assertThat(producer)
        .doesNotContain("com.flowzati.archone.messaging.outbox")
        .doesNotContain("java.sql.");
    assertThat(consumer)
        .doesNotContain("com.flowzati.archone.messaging.inbox.infrastructure")
        .doesNotContain("java.sql.");
  }

  private String readProductionSources(Path sourceRoot) throws IOException {
    StringBuilder content = new StringBuilder();
    try (var sources = Files.walk(sourceRoot)) {
      for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        content.append(Files.readString(source)).append('\n');
      }
    }
    return content.toString();
  }

  private Path root() {
    return Path.of(System.getProperty("archone.rootDir"));
  }
}
