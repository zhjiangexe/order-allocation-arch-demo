package com.flowzati.archone.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContractTest {

    private boolean originalDbc;
    private boolean originalCheckPre;
    private boolean originalCheckPost;
    private boolean originalCheckInv;

    @BeforeEach
    void enableContractSwitches() {
        originalDbc = Contract.DBC;
        originalCheckPre = Contract.CHECK_PRE;
        originalCheckPost = Contract.CHECK_POST;
        originalCheckInv = Contract.CHECK_INV;
        Contract.DBC = true;
        Contract.CHECK_PRE = true;
        Contract.CHECK_POST = true;
        Contract.CHECK_INV = true;
    }

    @AfterEach
    void restoreContractSwitches() {
        Contract.DBC = originalDbc;
        Contract.CHECK_PRE = originalCheckPre;
        Contract.CHECK_POST = originalCheckPost;
        Contract.CHECK_INV = originalCheckInv;
    }

    @Test
    void evaluatesPassingLambdaCondition() {
        assertThatCode(() -> Contract.ensure(() -> true, "Email must be present"))
                .doesNotThrowAnyException();
    }

    @Test
    void failsFastWhenConditionIsFalse() {
        AtomicBoolean secondConditionEvaluated = new AtomicBoolean();

        assertThatThrownBy(() -> {
                    Contract.ensure(() -> false, "Email must be present");
                    Contract.ensure(() -> secondConditionEvaluated.getAndSet(true), "Email must be valid");
                })
                .isInstanceOfSatisfying(PostconditionViolationException.class, exception -> {
                    assertThat(exception.phase()).isEqualTo(ContractPhase.POSTCONDITION);
                    assertThat(exception).hasMessage("Email must be present");
                });

        assertThat(secondConditionEvaluated).isFalse();
    }

    @Test
    void stopsBeforeMethodBodyWhenPreconditionFails() {
        AtomicBoolean methodBodyExecuted = new AtomicBoolean();

        assertThatThrownBy(() -> {
                    Contract.require(() -> false, "Email must be present");
                    methodBodyExecuted.set(true);
                })
                .isInstanceOf(PreconditionViolationException.class);

        assertThat(methodBodyExecuted).isFalse();
    }

    @Test
    void requiresValueToBeNonNull() {
        assertThatCode(() -> Contract.requireNotNull("email", "Email must be present"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> Contract.requireNotNull(null, "Email must be present"))
                .isInstanceOfSatisfying(PreconditionViolationException.class, exception -> {
                    assertThat(exception.phase()).isEqualTo(ContractPhase.PRECONDITION);
                    assertThat(exception).hasMessage("Email must be present");
                });
    }

    @Test
    void recordsConditionEvaluationFailureAsCause() {
        IllegalStateException evaluationFailure = new IllegalStateException("broken predicate");

        assertThatThrownBy(() -> Contract.invariant(
                        () -> {
                            throw evaluationFailure;
                        },
                        "Email must be valid"))
                .isInstanceOfSatisfying(InvariantViolationException.class, exception -> {
                    assertThat(exception.getCause()).isSameAs(evaluationFailure);
                    assertThat(exception).hasMessage("Email must be valid");
                });
    }

    @Test
    void exceptionCarriesPlainDiagnosticMessage() {
        assertThatThrownBy(() -> Contract.require(() -> false, "Available stock must cover requested quantity"))
                .isInstanceOfSatisfying(PreconditionViolationException.class, exception -> {
                    assertThat(exception.phase()).isEqualTo(ContractPhase.PRECONDITION);
                    assertThat(exception).hasMessage("Available stock must cover requested quantity");
                });
    }

    @Test
    void skipsAllConditionsWhenDbcIsDisabled() {
        AtomicBoolean conditionEvaluated = new AtomicBoolean();
        Contract.DBC = false;

        Contract.require(() -> conditionEvaluated.getAndSet(true), "Email must be present");
        Contract.ensure(() -> conditionEvaluated.getAndSet(true), "Email must be present");
        Contract.invariant(() -> conditionEvaluated.getAndSet(true), "Email must be present");

        assertThat(conditionEvaluated).isFalse();
    }

    @Test
    void allowsEachContractPhaseToBeDisabledIndependently() {
        AtomicBoolean conditionEvaluated = new AtomicBoolean();
        Contract.CHECK_POST = false;

        Contract.ensure(() -> conditionEvaluated.getAndSet(true), "Email must be present");

        assertThat(conditionEvaluated).isFalse();
    }

    @Test
    void ignoresNestedContractsWhileEvaluatingACondition() {
        assertThatCode(() -> Contract.ensure(
                        () -> {
                            Contract.require(() -> false, "Email must be present");
                            return true;
                        },
                        "Email must be valid"))
                .doesNotThrowAnyException();
    }
}
