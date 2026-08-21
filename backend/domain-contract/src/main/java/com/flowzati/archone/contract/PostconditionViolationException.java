package com.flowzati.archone.contract;

public final class PostconditionViolationException extends ContractViolationException {

    public PostconditionViolationException(String message, Throwable evaluationCause) {
        super(ContractPhase.POSTCONDITION, message, evaluationCause);
    }
}
