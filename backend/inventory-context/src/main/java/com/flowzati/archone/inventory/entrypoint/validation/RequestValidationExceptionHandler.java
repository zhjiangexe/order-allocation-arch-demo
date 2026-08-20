package com.flowzati.archone.inventory.entrypoint.validation;

import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 將 inventory REST request 的 Bean Validation 錯誤轉成本地化的 400 回應。 */
@RestControllerAdvice(basePackages = "com.flowzati.archone.inventory")
@Import(RequestValidationMessageConfiguration.class)
public class RequestValidationExceptionHandler {

    private final RequestValidationMessageFormatter messageFormatter;

    public RequestValidationExceptionHandler(RequestValidationMessageFormatter messageFormatter) {
        this.messageFormatter = messageFormatter;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handle(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(messageFormatter.format(
                        exception.getBindingResult().getAllErrors().getFirst()));
    }
}
