package com.flowzati.archone.support.spring.web.validation;

import java.net.URI;
import java.util.List;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 將 Spring MVC request validation errors 統一轉為 RFC 9457 ProblemDetail。 */
@RestControllerAdvice
@Import(RequestValidationMessageConfiguration.class)
public class GlobalRestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI REQUEST_VALIDATION_TYPE = URI.create("urn:archone:problem:request-validation");
    private static final String DEFAULT_ERROR_CODE = "Invalid";

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
