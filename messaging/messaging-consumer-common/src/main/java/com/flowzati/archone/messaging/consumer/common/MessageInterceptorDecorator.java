package com.flowzati.archone.messaging.consumer.common;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageInterceptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adapts Tram-style receive/handle interceptor hooks into the single ordered consumer chain. */
public final class MessageInterceptorDecorator implements MessageHandlerDecorator {

  private final List<MessageInterceptor> interceptors;

  public MessageInterceptorDecorator(List<MessageInterceptor> interceptors) {
    if (interceptors == null || interceptors.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Message interceptors are required");
    }
    this.interceptors = List.copyOf(interceptors);
  }

  @Override
  public int order() {
    return MessageHandlerDecoratorOrders.INTERCEPTOR_LIFECYCLE;
  }

  @Override
  public ProcessingOutcome handle(
      MessageHandlerInvocation invocation,
      MessageHandlerDecoratorChain chain
  ) {
    Message message = invocation.message();
    MessageContext context = invocation.context();
    List<MessageInterceptor> received = new ArrayList<>(interceptors.size());
    List<MessageInterceptor> handling = new ArrayList<>(interceptors.size());
    ProcessingOutcome outcome = null;
    Throwable failure = null;

    try {
      for (MessageInterceptor interceptor : interceptors) {
        interceptor.preReceive(message, context);
        received.add(interceptor);
      }
      for (MessageInterceptor interceptor : interceptors) {
        interceptor.preHandle(message, context);
        handling.add(interceptor);
      }
      outcome = chain.invokeNext(invocation);
    } catch (RuntimeException | Error exception) {
      failure = exception;
    }

    Throwable postFailure = invokePostHooks(message, context, handling, received, failure);
    if (failure != null) {
      if (postFailure != null) {
        failure.addSuppressed(postFailure);
      }
      rethrow(failure);
    }
    if (postFailure != null) {
      rethrow(postFailure);
    }
    return outcome;
  }

  private Throwable invokePostHooks(
      Message message,
      MessageContext context,
      List<MessageInterceptor> handling,
      List<MessageInterceptor> received,
      Throwable handlingFailure
  ) {
    Throwable firstFailure = null;
    for (int index = handling.size() - 1; index >= 0; index--) {
      firstFailure = invokePostHandle(
          handling.get(index), message, context, handlingFailure, firstFailure);
    }
    for (int index = received.size() - 1; index >= 0; index--) {
      firstFailure = invokePostReceive(
          received.get(index), message, context, handlingFailure, firstFailure);
    }
    return firstFailure;
  }

  private Throwable invokePostHandle(
      MessageInterceptor interceptor,
      Message message,
      MessageContext context,
      Throwable handlingFailure,
      Throwable firstFailure
  ) {
    try {
      interceptor.postHandle(message, context, handlingFailure);
      return firstFailure;
    } catch (RuntimeException | Error exception) {
      return combine(firstFailure, exception);
    }
  }

  private Throwable invokePostReceive(
      MessageInterceptor interceptor,
      Message message,
      MessageContext context,
      Throwable handlingFailure,
      Throwable firstFailure
  ) {
    try {
      interceptor.postReceive(message, context, handlingFailure);
      return firstFailure;
    } catch (RuntimeException | Error exception) {
      return combine(firstFailure, exception);
    }
  }

  private Throwable combine(Throwable firstFailure, Throwable nextFailure) {
    if (firstFailure == null) {
      return nextFailure;
    }
    firstFailure.addSuppressed(nextFailure);
    return firstFailure;
  }

  private static void rethrow(Throwable failure) {
    if (failure instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    throw (Error) failure;
  }
}
