package com.flowzati.archone.messaging.testsupport;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.producer.common.MessageProducerImplementation;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory producer SPI probe for adapter and common-orchestration tests. */
public final class RecordingMessageProducerImplementation implements MessageProducerImplementation {

  private final List<SentMessage> sentMessages = new CopyOnWriteArrayList<>();

  @Override
  public void send(String destination, Message message) {
    sentMessages.add(new SentMessage(destination, message));
  }

  public List<SentMessage> sentMessages() {
    return List.copyOf(sentMessages);
  }

  public record SentMessage(String destination, Message message) {
    public SentMessage {
      if (destination == null || destination.isBlank() || message == null) {
        throw new IllegalArgumentException("Sent message fields are required");
      }
    }
  }
}
