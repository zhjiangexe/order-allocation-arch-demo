package com.flowzati.archone.messaging.autoconfigure;

import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.inbox.DuplicateMessageDetectorInboxRepo;
import com.flowzati.archone.messaging.inbox.InboxRepo;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Temporary application facade removed after Gate I migrates direct InboxRepo use. */
@AutoConfiguration(after = MessagingConsumerJdbcAutoConfiguration.class)
@ConditionalOnClass({InboxRepo.class, DuplicateMessageDetectorInboxRepo.class})
@ConditionalOnBean({MessagingTransactionTemplate.class, DuplicateMessageDetector.class})
@ConditionalOnProperty(
    prefix = "archone.messaging.consumer.inbox-compatibility",
    name = "enabled",
    matchIfMissing = true
)
public class MessagingLegacyInboxAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  InboxRepo inboxRepo(
      MessagingTransactionTemplate transactionTemplate,
      DuplicateMessageDetector duplicateMessageDetector
  ) {
    return new DuplicateMessageDetectorInboxRepo(transactionTemplate, duplicateMessageDetector);
  }
}
