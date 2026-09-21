package com.flowzati.archone.contract;

public abstract class ContractViolationException extends RuntimeException {

    private final ContractPhase phase;

    protected ContractViolationException(ContractPhase phase, String message, Throwable evaluationCause) {
        super(message, evaluationCause);
        this.phase = phase;
    }

    public ContractPhase phase() {
        return phase;
    }
}
