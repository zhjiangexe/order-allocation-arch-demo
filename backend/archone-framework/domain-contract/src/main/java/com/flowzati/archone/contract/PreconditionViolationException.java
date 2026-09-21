package com.flowzati.archone.contract;

public final class PreconditionViolationException extends ContractViolationException {

    public PreconditionViolationException(String message, Throwable evaluationCause) {
        super(ContractPhase.PRECONDITION, message, evaluationCause);
    }
}
