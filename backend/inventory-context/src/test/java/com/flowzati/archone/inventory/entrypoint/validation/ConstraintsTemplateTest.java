package com.flowzati.archone.inventory.entrypoint.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ConstraintsTemplateTest {

    private static final Set<String> JAKARTA_CONSTRAINTS = Set.of(
            "AssertFalse",
            "AssertTrue",
            "DecimalMax",
            "DecimalMin",
            "Digits",
            "Email",
            "Future",
            "FutureOrPresent",
            "Max",
            "Min",
            "Negative",
            "NegativeOrZero",
            "NotBlank",
            "NotEmpty",
            "NotNull",
            "Null",
            "Past",
            "PastOrPresent",
            "Pattern",
            "Positive",
            "PositiveOrZero",
            "Size");

    @ParameterizedTest
    @MethodSource("supportedLocales")
    @DisplayName("每個語系都包含所有 Jakarta 內建 constraint templates")
    void shouldContainEveryJakartaConstraintTemplate(Locale locale) {
        ResourceBundle templates = ResourceBundle.getBundle("constraints_template", locale);

        assertThat(templates.keySet()).containsExactlyInAnyOrderElementsOf(JAKARTA_CONSTRAINTS);
        templates
                .keySet()
                .forEach(key ->
                        new MessageFormat(templates.getString(key), locale).format(new Object[] {"Field", 1, 2}));
    }

    static Stream<Locale> supportedLocales() {
        return Stream.of(Locale.ROOT, Locale.ENGLISH, Locale.TAIWAN);
    }
}
