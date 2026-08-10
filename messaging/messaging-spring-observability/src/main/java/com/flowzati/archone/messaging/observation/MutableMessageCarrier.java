package com.flowzati.archone.messaging.observation;

import com.flowzati.archone.messaging.api.Message;
import java.util.Locale;
import java.util.Objects;

/** Mutable reference around an immutable message, used only while trace headers are injected. */
public final class MutableMessageCarrier {

  private Message message;

  public MutableMessageCarrier(Message message) {
    this.message = Objects.requireNonNull(message, "Message is required");
  }

  public Message message() {
    return message;
  }

  public void setHeader(String name, String value) {
    message = message.withHeader(normalize(name), value);
  }

  static String normalize(String name) {
    return Objects.requireNonNull(name, "Header name is required").toLowerCase(Locale.ROOT);
  }
}
