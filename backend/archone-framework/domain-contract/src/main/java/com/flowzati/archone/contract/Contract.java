package com.flowzati.archone.contract;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class Contract {

    public static boolean DBC;
    public static boolean CHECK_PRE;
    public static boolean CHECK_POST;
    public static boolean CHECK_INV;

    private static final ThreadLocal<Boolean> ENTERED = ThreadLocal.withInitial(() -> false);

    static {
        DBC = !"off".equals(System.getenv("DBC"));
        CHECK_PRE = DBC && !"off".equals(System.getenv("DBC_PRE"));
        CHECK_POST = DBC && !"off".equals(System.getenv("DBC_POST"));
        CHECK_INV = DBC && !"off".equals(System.getenv("DBC_INV"));
    }

    private Contract() {}

    public static void require(BooleanSupplier condition, String message) {
        if (!DBC || !CHECK_PRE || ENTERED.get()) {
            return;
        }
        evaluate(ContractPhase.PRECONDITION, condition, message);
    }

    public static void requireNotNull(Object value, String message) {
        require(() -> value != null, message);
    }

    public static void ensure(BooleanSupplier condition, String message) {
        if (!DBC || !CHECK_POST || ENTERED.get()) {
            return;
        }
        evaluate(ContractPhase.POSTCONDITION, condition, message);
    }

    public static void invariant(BooleanSupplier condition, String message) {
        if (!DBC || !CHECK_INV || ENTERED.get()) {
            return;
        }
        evaluate(ContractPhase.INVARIANT, condition, message);
    }

    private static void evaluate(ContractPhase phase, BooleanSupplier condition, String message) {
        Objects.requireNonNull(condition, "Condition is required");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message is required");
        }

        Throwable evaluationCause = null;
        try {
            ENTERED.set(true);
            if (condition.getAsBoolean()) {
                return;
            }
        } catch (RuntimeException | AssertionError exception) {
            evaluationCause = exception;
        } finally {
            ENTERED.set(false);
        }

        throw violationException(phase, message, evaluationCause);
    }

    private static ContractViolationException violationException(
            ContractPhase phase, String message, Throwable evaluationCause) {
        return switch (phase) {
            case PRECONDITION -> new PreconditionViolationException(message, evaluationCause);
            case POSTCONDITION -> new PostconditionViolationException(message, evaluationCause);
            case INVARIANT -> new InvariantViolationException(message, evaluationCause);
        };
    }
}
