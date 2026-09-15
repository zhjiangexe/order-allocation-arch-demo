package com.flowzati.archone.support.spring.web.validation;

import com.flowzati.archone.contract.ContractViolationException;
import com.flowzati.archone.foundation.error.BusinessErrorKind;
import com.flowzati.archone.foundation.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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

/** 將 request validation、預期的 business rejection 與內部 Contract violation 統一轉為 RFC 9457 ProblemDetail。 */
@RestControllerAdvice
@Import(RequestValidationMessageConfiguration.class)
public class GlobalRestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalRestExceptionHandler.class);
    private static final URI REQUEST_VALIDATION_TYPE = URI.create("urn:archone:problem:request-validation");
    private static final URI INVALID_REQUEST_TYPE = URI.create("urn:archone:problem:invalid-request");
    private static final URI RESOURCE_NOT_FOUND_TYPE = URI.create("urn:archone:problem:resource-not-found");
    private static final URI DATA_CONFLICT_TYPE = URI.create("urn:archone:problem:data-conflict");
    private static final URI DATA_INTEGRITY_TYPE = URI.create("urn:archone:problem:data-integrity-violation");
    private static final URI CONTRACT_VIOLATION_TYPE = URI.create("urn:archone:problem:internal-contract-violation");
    private static final String BUSINESS_ERROR_TYPE_PREFIX = "urn:archone:problem:";
    private static final String DEFAULT_ERROR_CODE = "Invalid";
    private static final String INVALID_REQUEST_CODE = "INVALID_REQUEST";
    private static final String RESOURCE_NOT_FOUND_CODE = "RESOURCE_NOT_FOUND";
    private static final String DATA_CONFLICT_CODE = "DATA_CONFLICT";
    private static final String DATA_INTEGRITY_CODE = "DATA_INTEGRITY_VIOLATION";
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

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {
        HttpStatus status = businessStatus(exception.kind());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setType(businessErrorType(exception));
        problem.setTitle(businessTitle(exception.kind()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", exception.errorCode().value());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(
            IllegalArgumentException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setType(INVALID_REQUEST_TYPE);
        problem.setTitle("Invalid request");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", INVALID_REQUEST_CODE);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(
            NoSuchElementException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setType(RESOURCE_NOT_FOUND_TYPE);
        problem.setTitle("Resource not found");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", RESOURCE_NOT_FOUND_CODE);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        boolean conflict = isUniqueConstraintViolation(exception);
        HttpStatus status = conflict ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        String detail = conflict
                ? "The request conflicts with existing data"
                : "The request references data that does not exist or is invalid";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(conflict ? DATA_CONFLICT_TYPE : DATA_INTEGRITY_TYPE);
        problem.setTitle(conflict ? "Data conflict" : "Invalid request");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", conflict ? DATA_CONFLICT_CODE : DATA_INTEGRITY_CODE);
        return ResponseEntity.status(status).body(problem);
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

    private static boolean isUniqueConstraintViolation(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            if (cause.getMessage() != null) {
                String message = cause.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("duplicate key") || message.contains("unique constraint")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static HttpStatus businessStatus(BusinessErrorKind kind) {
        return switch (kind) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case ALREADY_EXISTS, INVALID_STATE, CONFLICT, STALE_STATE -> HttpStatus.CONFLICT;
        };
    }

    private static String businessTitle(BusinessErrorKind kind) {
        return switch (kind) {
            case NOT_FOUND -> "Business object not found";
            case ALREADY_EXISTS -> "Business object already exists";
            case RULE_VIOLATION -> "Business rule violation";
            case INVALID_STATE -> "Invalid business state";
            case CONFLICT -> "Business conflict";
            case STALE_STATE -> "Business state is stale";
            case ACCESS_DENIED -> "Business operation denied";
        };
    }

    private static URI businessErrorType(BusinessException exception) {
        String type = exception.errorCode().value().toLowerCase(Locale.ROOT).replace('_', '-');
        return URI.create(BUSINESS_ERROR_TYPE_PREFIX + type);
    }
}
