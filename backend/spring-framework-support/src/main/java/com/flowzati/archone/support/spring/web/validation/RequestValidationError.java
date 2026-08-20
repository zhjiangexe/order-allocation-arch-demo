package com.flowzati.archone.support.spring.web.validation;

/** RFC 9457 ProblemDetail 的單筆 request validation extension。 */
public record RequestValidationError(String field, String code, String message) {}
