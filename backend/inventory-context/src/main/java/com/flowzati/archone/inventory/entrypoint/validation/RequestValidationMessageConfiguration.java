package com.flowzati.archone.inventory.entrypoint.validation;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/** 建立只供 REST request validation 使用的多國語言訊息來源。 */
@Configuration(proxyBeanMethods = false)
class RequestValidationMessageConfiguration {

    @Bean
    RequestValidationMessageFormatter requestValidationMessageFormatter() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasenames("constraints_template", "request_field");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        return new RequestValidationMessageFormatter(messageSource);
    }
}
