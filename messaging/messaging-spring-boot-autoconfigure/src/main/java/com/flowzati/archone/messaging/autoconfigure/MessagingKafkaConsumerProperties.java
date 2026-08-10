package com.flowzati.archone.messaging.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.kafka.listener.ContainerProperties;

/** Default operational policy for programmatic Kafka subscriptions. */
@ConfigurationProperties("archone.messaging.consumer.kafka")
public class MessagingKafkaConsumerProperties {

  private int concurrency = 1;
  private ContainerProperties.AckMode ackMode = ContainerProperties.AckMode.BATCH;
  private boolean missingTopicsFatal = true;
  private boolean observationEnabled;
  private Duration shutdownTimeout = Duration.ofSeconds(10);

  public int getConcurrency() {
    return concurrency;
  }

  public void setConcurrency(int concurrency) {
    this.concurrency = concurrency;
  }

  public ContainerProperties.AckMode getAckMode() {
    return ackMode;
  }

  public void setAckMode(ContainerProperties.AckMode ackMode) {
    this.ackMode = ackMode;
  }

  public boolean isMissingTopicsFatal() {
    return missingTopicsFatal;
  }

  public void setMissingTopicsFatal(boolean missingTopicsFatal) {
    this.missingTopicsFatal = missingTopicsFatal;
  }

  public boolean isObservationEnabled() {
    return observationEnabled;
  }

  public void setObservationEnabled(boolean observationEnabled) {
    this.observationEnabled = observationEnabled;
  }

  public Duration getShutdownTimeout() {
    return shutdownTimeout;
  }

  public void setShutdownTimeout(Duration shutdownTimeout) {
    this.shutdownTimeout = shutdownTimeout;
  }
}
