package com.flowzati.archone.inventory.entrypoint.validation;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

/** 以 constraint 類型與 request 欄位名稱組合本地化驗證訊息。 */
public final class RequestValidationMessageFormatter {

    private final MessageSource messageSource;

    public RequestValidationMessageFormatter(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String format(ObjectError error) {
        Locale locale = LocaleContextHolder.getLocale();
        if (!(error instanceof FieldError fieldError)) {
            return messageSource.getMessage(error, locale);
        }

        Object[] arguments = fieldError.getArguments();
        if (arguments == null || arguments.length == 0) {
            return messageSource.getMessage(fieldError, locale);
        }

        String fieldKey = fieldError.getObjectName() + "." + fieldError.getField();
        String fieldLabel = messageSource.getMessage(fieldKey, null, fieldError.getField(), locale);

        Object[] localizedArguments = arguments.clone();
        localizedArguments[0] = fieldLabel;
        for (int index = 1; index < localizedArguments.length; index++) {
            if (localizedArguments[index] instanceof Boolean value) {
                localizedArguments[index] = value ? 1 : 0;
            }
        }
        return messageSource.getMessage(
                fieldError.getCode(), localizedArguments, fieldError.getDefaultMessage(), locale);
    }
}
