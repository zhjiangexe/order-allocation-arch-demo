package com.flowzati.archone.support.spring.web.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.validation.FieldError;

class RequestValidationMessageFormatterTest {

    @Test
    @DisplayName("直接使用 FieldError 的 MessageSourceResolvable 解析 constraint 與欄位名稱")
    void shouldResolveSpringNativeValidationMessage() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("NotNull", Locale.ENGLISH, "{0} is required");
        messageSource.addMessage("confirmStockReceiptRequest.quantity", Locale.ENGLISH, "Received quantity");
        RequestValidationMessageFormatter formatter = new RequestValidationMessageFormatter(messageSource);
        FieldError error = new FieldError(
                "confirmStockReceiptRequest",
                "quantity",
                null,
                false,
                new String[] {"NotNull.confirmStockReceiptRequest.quantity", "NotNull"},
                new Object[] {
                    new DefaultMessageSourceResolvable(
                            new String[] {"confirmStockReceiptRequest.quantity", "quantity"}, "quantity")
                },
                "must not be null");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            assertThat(formatter.format(error)).isEqualTo("Received quantity is required");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
