package com.flowzati.archone.support.spring.web.validation;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.DefaultMessageSourceResolvable;

/** 直接解析 Spring validation 產生的 {@link MessageSourceResolvable}。 */
public final class RequestValidationMessageFormatter {

    private final MessageSource messageSource;

    public RequestValidationMessageFormatter(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String format(MessageSourceResolvable error) {
        return messageSource.getMessage(withMessageFormatCompatibleArguments(error), currentLocale());
    }

    public String message(String code, String defaultMessage) {
        return messageSource.getMessage(code, null, defaultMessage, currentLocale());
    }

    private static MessageSourceResolvable withMessageFormatCompatibleArguments(MessageSourceResolvable error) {
        Object[] arguments = error.getArguments();
        if (arguments == null || arguments.length == 0) {
            return error;
        }

        Object[] compatibleArguments = arguments.clone();
        boolean changed = false;
        for (int index = 0; index < compatibleArguments.length; index++) {
            if (compatibleArguments[index] instanceof Boolean value) {
                compatibleArguments[index] = value ? 1 : 0;
                changed = true;
            }
        }
        return changed
                ? new DefaultMessageSourceResolvable(error.getCodes(), compatibleArguments, error.getDefaultMessage())
                : error;
    }

    private static Locale currentLocale() {
        return LocaleContextHolder.getLocale();
    }
}
