package com.flowzati.archone.foundation.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void exposesStableCodeCategoryMessageAndCause() {
        RuntimeException cause = new RuntimeException("database conflict");

        BusinessException exception = new StaleStateException(TestErrorCode.STATE_STALE, "State changed", cause);

        assertThat(exception.errorCode()).isEqualTo(TestErrorCode.STATE_STALE);
        assertThat(exception.kind()).isEqualTo(BusinessErrorKind.STALE_STATE);
        assertThat(exception).hasMessage("State changed").hasCause(cause);
    }

    @Test
    void rejectsBlankCodesAndMessagesAtConstructionTime() {
        assertThatThrownBy(() -> new NotFoundException(() -> "", "Object is missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Error code value is required");
        assertThatThrownBy(() -> new NotFoundException(TestErrorCode.OBJECT_NOT_FOUND, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Business error message is required");
    }

    @Test
    void classifiesApplicationPolicyRejectionAsRuleViolation() {
        BusinessException exception = new ApplicationRuleViolationException(
                TestErrorCode.APPLICATION_POLICY_REJECTED, "Application policy rejected the command");

        assertThat(exception.errorCode()).isEqualTo(TestErrorCode.APPLICATION_POLICY_REJECTED);
        assertThat(exception.kind()).isEqualTo(BusinessErrorKind.RULE_VIOLATION);
    }

    private enum TestErrorCode implements ErrorCode {
        OBJECT_NOT_FOUND,
        STATE_STALE,
        APPLICATION_POLICY_REJECTED;

        @Override
        public String value() {
            return name();
        }
    }
}
