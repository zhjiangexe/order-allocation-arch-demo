package com.flowzati.archone.stock.application.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({PostgreSQLTestConfiguration.class, AllocationRetryTransactionIntegrationTest.ProbeConfiguration.class})
class AllocationRetryTransactionIntegrationTest {

  @Autowired
  private AllocationRetryExecutor retryExecutor;

  @Autowired
  private RetryingTransactionProbe probe;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearProbeRows() {
    jdbcTemplate.update("DELETE FROM event_inbox WHERE event_type = 'RetryProbe'");
    probe.reset();
  }

  @Test
  @DisplayName("每次 optimistic-lock retry 應進入新的 transaction")
  void shouldRunEachRetryInANewTransaction() {
    probe.failFirst(2, false);

    retryExecutor.execute(context(), probe::execute);

    assertThat(probe.transactionIds()).hasSize(3).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("重試耗盡時每次 transaction 寫入都應 rollback")
  void shouldRollBackEveryTransactionWhenRetryIsExhausted() {
    probe.failFirst(3, true);

    assertThatThrownBy(() -> retryExecutor.execute(context(), probe::execute))
        .isInstanceOf(AllocationConcurrencyExhaustedException.class);

    assertThat(probe.transactionIds()).hasSize(3).doesNotHaveDuplicates();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM event_inbox WHERE event_type = 'RetryProbe'", Integer.class)).isZero();
  }

  private AllocationRetryContext context() {
    return new AllocationRetryContext(
        "allocate-order", UUID.randomUUID(), UUID.randomUUID().toString(), "SKU-RETRY");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfiguration {

    @Bean
    RetryingTransactionProbe retryingTransactionProbe(JdbcTemplate jdbcTemplate) {
      return new RetryingTransactionProbe(jdbcTemplate);
    }
  }

  static class RetryingTransactionProbe {

    private final JdbcTemplate jdbcTemplate;
    private final List<Long> transactionIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private int failures;
    private boolean writeInbox;

    RetryingTransactionProbe(JdbcTemplate jdbcTemplate) {
      this.jdbcTemplate = jdbcTemplate;
    }

    void failFirst(int failures, boolean writeInbox) {
      this.failures = failures;
      this.writeInbox = writeInbox;
    }

    @Transactional
    public void execute() {
      transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
      if (writeInbox) {
        jdbcTemplate.update(
            "INSERT INTO event_inbox (event_id, event_type, processed_at) "
                + "VALUES (?::uuid, 'RetryProbe', CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString());
      }
      if (attempts.incrementAndGet() <= failures) {
        throw new OptimisticLockingFailureException("forced conflict");
      }
    }

    List<Long> transactionIds() {
      return List.copyOf(transactionIds);
    }

    void reset() {
      transactionIds.clear();
      attempts.set(0);
      failures = 0;
      writeInbox = false;
    }
  }
}
