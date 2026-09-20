package com.flowzati.archone.orderfulfillment.entrypoint.rest;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Remote cancellation failures must not be represented as a business rejection. */
@RestControllerAdvice(assignableTypes = OrderCancellationRest.class)
public class CancellationRpcExceptionHandler {

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ProblemDetail> downstreamResponse(RestClientResponseException exception) {
        int downstreamStatus = exception.getStatusCode().value();
        HttpStatus status =
                switch (downstreamStatus) {
                    case 400 -> HttpStatus.BAD_REQUEST;
                    case 404 -> HttpStatus.NOT_FOUND;
                    case 409 -> HttpStatus.CONFLICT;
                    case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
                    case 504 -> HttpStatus.GATEWAY_TIMEOUT;
                    default -> HttpStatus.BAD_GATEWAY;
                };
        return problem(status, "DOWNSTREAM_RPC_ERROR", downstreamStatus >= 500);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ProblemDetail> transportFailure(ResourceAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return problem(HttpStatus.GATEWAY_TIMEOUT, "DOWNSTREAM_TIMEOUT", true);
            }
        }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DOWNSTREAM_UNAVAILABLE", true);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, boolean outcomeUnknown) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                status,
                outcomeUnknown
                        ? "Cancellation outcome may be unknown; retry with the same requestId and payload"
                        : "Cancellation request was rejected by a downstream service");
        detail.setType(URI.create("urn:archone:problem:downstream-rpc"));
        detail.setTitle("Cancellation service call failed");
        detail.setProperty("code", code);
        return ResponseEntity.status(status).body(detail);
    }
}
