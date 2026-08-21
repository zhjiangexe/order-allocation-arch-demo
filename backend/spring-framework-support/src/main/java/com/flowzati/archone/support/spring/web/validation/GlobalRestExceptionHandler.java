package com.flowzati.archone.support.spring.web.validation;

import com.flowzati.archone.contract.ContractViolationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 將 Spring MVC request validation 與內部 Contract violation 統一轉為 RFC 9457 ProblemDetail。 */
@RestControllerAdvice
@Import(RequestValidationMessageConfiguration.class)
public class GlobalRestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalRestExceptionHandler.class);
    private static final URI REQUEST_VALIDATION_TYPE = URI.create("urn:archone:problem:request-validation");
    private static final URI CONTRACT_VIOLATION_TYPE = URI.create("urn:archone:problem:internal-contract-violation");
    private static final String DEFAULT_ERROR_CODE = "Invalid";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    private static final String INTERNAL_ERROR_TITLE = "Internal server error";
    private static final String INTERNAL_ERROR_DETAIL = "The system could not complete the request";

    private final RequestValidationMessageFormatter messageFormatter;

    public GlobalRestExceptionHandler(RequestValidationMessageFormatter messageFormatter) {
        this.messageFormatter = messageFormatter;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<RequestValidationError> errors = exception.getBindingResult().getAllErrors().stream()
                .map(this::toValidationError)
                .toList();
        return handleExceptionInternal(
                exception, requestValidationProblem(status, request, errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<RequestValidationError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> toValidationError(result, error)))
                .toList();
        return handleExceptionInternal(
                exception, requestValidationProblem(status, request, errors), headers, status, request);
    }

    @ExceptionHandler(ContractViolationException.class)
    public ResponseEntity<ProblemDetail> handleContractViolation(
            ContractViolationException exception, HttpServletRequest request) {
        String incidentId = UUID.randomUUID().toString();

        LOGGER.error(
                "Contract violation: incidentId={}, phase={}, message={}",
                incidentId,
                exception.phase(),
                exception.getMessage(),
                exception);

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL);
        problem.setType(CONTRACT_VIOLATION_TYPE);
        problem.setTitle(INTERNAL_ERROR_TITLE);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", INTERNAL_ERROR_CODE);
        problem.setProperty("incidentId", incidentId);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private ProblemDetail requestValidationProblem(
            HttpStatusCode status, WebRequest request, List<RequestValidationError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                messageFormatter.message(
                        "problemDetail.requestValidation.detail", "One or more request fields are invalid"));
        problem.setType(REQUEST_VALIDATION_TYPE);
        problem.setTitle(
                messageFormatter.message("problemDetail.requestValidation.title", "Request validation failed"));
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        problem.setProperty("errors", errors);
        return problem;
    }

    private RequestValidationError toValidationError(ObjectError error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : null;
        return new RequestValidationError(field, constraintCode(error), messageFormatter.format(error));
    }

    private RequestValidationError toValidationError(ParameterValidationResult result, MessageSourceResolvable error) {
        String field = error instanceof FieldError fieldError
                ? fieldError.getField()
                : result.getMethodParameter().getParameterName();
        return new RequestValidationError(field, constraintCode(error), messageFormatter.format(error));
    }

    private static String constraintCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? DEFAULT_ERROR_CODE : codes[codes.length - 1];
    }
}
