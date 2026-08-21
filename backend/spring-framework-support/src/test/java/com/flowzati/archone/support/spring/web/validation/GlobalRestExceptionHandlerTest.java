package com.flowzati.archone.support.spring.web.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contract.ContractViolationException;
import com.flowzati.archone.contract.InvariantViolationException;
import com.flowzati.archone.contract.PostconditionViolationException;
import com.flowzati.archone.contract.PreconditionViolationException;
import java.net.URI;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
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

    private static Stream<ContractViolationException> contractViolations() {
        return Stream.of(
                new PreconditionViolationException("Reservation request belongs to another stock quant", null),
                new PostconditionViolationException("Reserved quantity did not increase as expected", null),
                new InvariantViolationException("Reserved quantity exceeds on-hand quantity", null));
    }

    private static void assertThatCodeIsUuid(Object incidentId) {
        assertThat(incidentId).isInstanceOf(String.class);
        assertThat(UUID.fromString((String) incidentId)).isNotNull();
    }
}
