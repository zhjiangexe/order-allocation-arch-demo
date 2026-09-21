package com.flowzati.archone.contract;

public final class InvariantViolationException extends ContractViolationException {

    public InvariantViolationException(String message, Throwable evaluationCause) {
        super(ContractPhase.INVARIANT, message, evaluationCause);
    }
}
