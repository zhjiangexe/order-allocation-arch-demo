package com.flowzati.archone.messaging.consumer.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageInterceptor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageInterceptorDecoratorTest {

  @Test
  void runsReceiveAndHandleHooksAsOneNestedLifecycle() {
    List<String> calls = new ArrayList<>();
    MessageInterceptorDecorator decorator = new MessageInterceptorDecorator(List.of(
        interceptor("first", calls), interceptor("second", calls)));
    MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
        List.of(decorator),
        invocation -> {
          calls.add("handler");
          return ProcessingOutcome.PROCESSED;
        });

    assertThat(chain.invokeNext(invocation())).isEqualTo(ProcessingOutcome.PROCESSED);
    assertThat(calls).containsExactly(
        "first.preReceive", "second.preReceive",
        "first.preHandle", "second.preHandle",
        "handler",
        "second.postHandle.success", "first.postHandle.success",
        "second.postReceive.success", "first.postReceive.success");
  }

  @Test
  void propagatesTheOriginalHandlerFailureToAllPostHooks() {
    List<String> calls = new ArrayList<>();
    IllegalStateException handlerFailure = new IllegalStateException("handler failed");
    MessageInterceptorDecorator decorator = new MessageInterceptorDecorator(
        List.of(interceptor("only", calls)));
    MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
        List.of(decorator),
        invocation -> {
          throw handlerFailure;
        });

    assertThatThrownBy(() -> chain.invokeNext(invocation())).isSameAs(handlerFailure);
    assertThat(calls).containsExactly(
        "only.preReceive", "only.preHandle",
        "only.postHandle.handler failed", "only.postReceive.handler failed");
  }

  private MessageInterceptor interceptor(String name, List<String> calls) {
    return new MessageInterceptor() {
      @Override
      public void preReceive(Message message) {
        calls.add(name + ".preReceive");
      }

      @Override
      public void preHandle(Message message) {
        calls.add(name + ".preHandle");
      }

      @Override
      public void postHandle(Message message, Throwable failure) {
        calls.add(name + ".postHandle." + result(failure));
      }

      @Override
      public void postReceive(Message message, Throwable failure) {
        calls.add(name + ".postReceive." + result(failure));
      }
    };
  }

  private String result(Throwable failure) {
    return failure == null ? "success" : failure.getMessage();
  }

  private MessageHandlerInvocation invocation() {
    return new MessageHandlerInvocation(
        MessageBuilder.withPayload("{}")
            .withId(UUID.randomUUID())
            .withType("example.v1")
            .withPartitionId("order-1")
            .build(),
        new MessageContext("subscriber", "order-events", 1));
  }
}
