package com.flowzati.archone.support.spring.web.validation;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/** 建立獨立於 application configuration 的 validation message source。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpringWebValidationProperties.class)
class RequestValidationMessageConfiguration {

    static final String MESSAGE_SOURCE_BEAN = "requestValidationMessageSource";

    @Bean(MESSAGE_SOURCE_BEAN)
    MessageSource requestValidationMessageSource(SpringWebValidationProperties properties) {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(properties.messageBasenames().toArray(String[]::new));
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setCacheMillis(properties.messageCacheDuration().toMillis());
        return messageSource;
    }

    @Bean
    RequestValidationMessageFormatter requestValidationMessageFormatter(
            @Qualifier(MESSAGE_SOURCE_BEAN) MessageSource messageSource) {
        return new RequestValidationMessageFormatter(messageSource);
    }
}
