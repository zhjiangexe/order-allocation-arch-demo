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
import java.net.SocketTimeoutException;
import java.net.URI;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

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

    @Test
    void mapsIllegalArgumentsToInvalidRequestProblemDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/stock-receipts");

        ResponseEntity<ProblemDetail> response =
                handler.handleInvalidRequest(new IllegalArgumentException("Location is outside facility"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).isEqualTo(URI.create("urn:archone:problem:invalid-request"));
        assertThat(problem.getTitle()).isEqualTo("Invalid request");
        assertThat(problem.getDetail()).isEqualTo("Location is outside facility");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/stock-receipts"));
        assertThat(problem.getProperties()).hasSize(1).containsEntry("code", "INVALID_REQUEST");
    }

    @Test
    void mapsMissingResourcesToNotFoundProblemDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/orders/order-1");

        ResponseEntity<ProblemDetail> response =
                handler.handleResourceNotFound(new NoSuchElementException("Order not found"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).isEqualTo(URI.create("urn:archone:problem:resource-not-found"));
        assertThat(problem.getTitle()).isEqualTo("Resource not found");
        assertThat(problem.getDetail()).isEqualTo("Order not found");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/orders/order-1"));
        assertThat(problem.getProperties()).hasSize(1).containsEntry("code", "RESOURCE_NOT_FOUND");
    }

    @Test
    void mapsUniqueConstraintViolationsToDataConflicts() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/orders");
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "Could not persist order", new SQLException("duplicate key", "23505"));

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).isEqualTo(URI.create("urn:archone:problem:data-conflict"));
        assertThat(problem.getTitle()).isEqualTo("Data conflict");
        assertThat(problem.getDetail()).isEqualTo("The request conflicts with existing data");
        assertThat(problem.getProperties()).hasSize(1).containsEntry("code", "DATA_CONFLICT");
    }

    @Test
    void mapsOtherDataIntegrityViolationsToInvalidRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/orders");

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("foreign key violation"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).isEqualTo(URI.create("urn:archone:problem:data-integrity-violation"));
        assertThat(problem.getTitle()).isEqualTo("Invalid request");
        assertThat(problem.getDetail()).isEqualTo("The request references data that does not exist or is invalid");
        assertThat(problem.getProperties()).hasSize(1).containsEntry("code", "DATA_INTEGRITY_VIOLATION");
    }

    @Test
    void hidesDownstreamResponseDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/orders/order-1/cancellation-requests");

        ResponseEntity<ProblemDetail> response = handler.handleDownstreamResponse(
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "sensitive downstream message"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).isEqualTo(URI.create("urn:archone:problem:downstream-service"));
        assertThat(problem.getDetail()).isEqualTo("The system could not complete the request at this time");
        assertThat(problem.getProperties())
                .containsEntry("code", "DOWNSTREAM_SERVICE_ERROR")
                .doesNotContainValue("sensitive downstream message");
        assertThatCodeIsUuid(problem.getProperties().get("incidentId"));
    }

    @Test
    void mapsDownstreamTimeoutWithoutExposingTransportDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/orders/order-1/cancellation-requests");

        ResponseEntity<ProblemDetail> response = handler.handleDownstreamTransportFailure(
                new ResourceAccessException("internal host timed out", new SocketTimeoutException("read timed out")),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).isNotNull();
        ProblemDetail problem = response.getBody();
        assertThat(problem.getDetail()).isEqualTo("The system could not complete the request at this time");
        assertThat(problem.getProperties()).containsEntry("code", "DOWNSTREAM_SERVICE_TIMEOUT");
        assertThat(problem.getProperties().values()).doesNotContain("internal host timed out", "read timed out");
        assertThatCodeIsUuid(problem.getProperties().get("incidentId"));
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
