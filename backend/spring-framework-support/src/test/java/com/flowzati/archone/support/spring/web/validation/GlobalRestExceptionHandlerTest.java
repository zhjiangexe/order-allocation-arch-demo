package com.flowzati.archone.support.spring.web.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contract.ContractViolationException;
import com.flowzati.archone.contract.InvariantViolationException;
import com.flowzati.archone.contract.PostconditionViolationException;
import com.flowzati.archone.contract.PreconditionViolationException;
import com.flowzati.archone.foundation.error.AlreadyExistsException;
import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.foundation.error.BusinessAccessDeniedException;
import com.flowzati.archone.foundation.error.BusinessException;
import com.flowzati.archone.foundation.error.DomainRuleViolationException;
import com.flowzati.archone.foundation.error.ErrorCode;
import com.flowzati.archone.foundation.error.InvalidStateTransitionException;
import com.flowzati.archone.foundation.error.NotFoundException;
import com.flowzati.archone.foundation.error.StaleStateException;
import java.net.URI;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalRestExceptionHandlerTest {

    private final GlobalRestExceptionHandler handler =
            new GlobalRestExceptionHandler(new RequestValidationMessageFormatter(new StaticMessageSource()));

    @ParameterizedTest
    @MethodSource("contractViolations")
    void mapsContractViolationsToSafeInternalServerError(ContractViolationException exception) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/stock-quants/quant-1/reservations");

        ResponseEntity<ProblemDetail> response = handler.handleContractViolation(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();

        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).isEqualTo(URI.create("urn:archone:problem:internal-contract-violation"));
        assertThat(problem.getTitle()).isEqualTo("Internal server error");
        assertThat(problem.getDetail()).isEqualTo("The system could not complete the request");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/stock-quants/quant-1/reservations"));
        assertThat(problem.getProperties())
                .containsEntry("code", "INTERNAL_ERROR")
                .doesNotContainKeys("phase", "message", "cause", "stackTrace");
        assertThat(problem.getProperties()).containsKey("incidentId");
        assertThatCodeIsUuid(problem.getProperties().get("incidentId"));
    }

    @ParameterizedTest
    @MethodSource("businessFailures")
    void mapsBusinessFailuresToProblemDetails(
            BusinessException exception, HttpStatus expectedStatus, String expectedTitle) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/orders/order-1");

        ResponseEntity<ProblemDetail> response = handler.handleBusinessException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).isEqualTo(URI.create("urn:archone:problem:" + expectedType(exception)));
        assertThat(problem.getTitle()).isEqualTo(expectedTitle);
        assertThat(problem.getDetail()).isEqualTo(exception.getMessage());
        assertThat(problem.getInstance()).isEqualTo(URI.create("/orders/order-1"));
        assertThat(problem.getProperties())
                .hasSize(1)
                .containsEntry("code", exception.errorCode().value());
    }

    private static Stream<ContractViolationException> contractViolations() {
        return Stream.of(
                new PreconditionViolationException("Reservation request belongs to another stock quant", null),
                new PostconditionViolationException("Reserved quantity did not increase as expected", null),
                new InvariantViolationException("Reserved quantity exceeds on-hand quantity", null));
    }

    private static Stream<Arguments> businessFailures() {
        return Stream.of(
                Arguments.of(
                        new NotFoundException(TestErrorCode.OBJECT_NOT_FOUND, "Object is missing"),
                        HttpStatus.NOT_FOUND,
                        "Business object not found"),
                Arguments.of(
                        new AlreadyExistsException(TestErrorCode.OBJECT_ALREADY_EXISTS, "Object exists"),
                        HttpStatus.CONFLICT,
                        "Business object already exists"),
                Arguments.of(
                        new DomainRuleViolationException(TestErrorCode.RULE_REJECTED, "Rule rejected"),
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Business rule violation"),
                Arguments.of(
                        new InvalidStateTransitionException(TestErrorCode.INVALID_STATE, "State rejected"),
                        HttpStatus.CONFLICT,
                        "Invalid business state"),
                Arguments.of(
                        new ApplicationConflictException(TestErrorCode.CONFLICT, "Facts conflict"),
                        HttpStatus.CONFLICT,
                        "Business conflict"),
                Arguments.of(
                        new StaleStateException(TestErrorCode.STATE_STALE, "State changed"),
                        HttpStatus.CONFLICT,
                        "Business state is stale"),
                Arguments.of(
                        new BusinessAccessDeniedException(TestErrorCode.ACCESS_DENIED, "Ownership rejected"),
                        HttpStatus.FORBIDDEN,
                        "Business operation denied"));
    }

    private static String expectedType(BusinessException exception) {
        return exception.errorCode().value().toLowerCase().replace('_', '-');
    }

    private static void assertThatCodeIsUuid(Object incidentId) {
        assertThat(incidentId).isInstanceOf(String.class);
        assertThat(UUID.fromString((String) incidentId)).isNotNull();
    }

    private enum TestErrorCode implements ErrorCode {
        OBJECT_NOT_FOUND,
        OBJECT_ALREADY_EXISTS,
        RULE_REJECTED,
        INVALID_STATE,
        CONFLICT,
        STATE_STALE,
        ACCESS_DENIED;

        @Override
        public String value() {
            return name();
        }
    }
}
