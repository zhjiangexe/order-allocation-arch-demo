package com.flowzati.archone.messaging.spring.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class SpringMessagingTransactionTemplateTest {

  @Test
  void executesThePureCallbackThroughSpringTransactionOperations() {
    TransactionOperations operations = mock(TransactionOperations.class);
    when(operations.execute(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      TransactionCallback<String> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });
    SpringMessagingTransactionTemplate template =
        new SpringMessagingTransactionTemplate(operations, () -> true);

    assertThat(template.execute(() -> "committed-value")).isEqualTo("committed-value");
    assertThat(template.isActive()).isTrue();
  }

  @Test
  void requiresAnActualCallerTransaction() {
    SpringMessagingTransactionTemplate template = new SpringMessagingTransactionTemplate(
        mock(TransactionOperations.class), () -> false);

    assertThatThrownBy(template::requireActive)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No active caller transaction for transactional message production");
  }
}
