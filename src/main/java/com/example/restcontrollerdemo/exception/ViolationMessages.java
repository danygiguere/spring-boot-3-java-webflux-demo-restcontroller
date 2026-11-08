package com.example.restcontrollerdemo.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.stereotype.Component;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;

/**
 * Localizes Bean Validation messages in the REQUEST's locale.
 *
 * <p>{@code @Valid} runs during argument resolution, where WebFlux has no locale context: the message is interpolated
 * in the JVM default locale. Each violation keeps its template ({@code {post.content.required}}), so it is
 * interpolated again here with the locale resolved from {@code Accept-Language}.
 */
@Component
public class ViolationMessages {

    private final MessageInterpolator interpolator;
    private final MessageSource messageSource;

    public ViolationMessages(ValidatorFactory validatorFactory, MessageSource messageSource) {
        this.interpolator = validatorFactory.getMessageInterpolator();
        this.messageSource = messageSource;
    }

    /** A {@code @Valid @RequestBody} field error. */
    public String of(ObjectError error, Locale locale) {
        return error.contains(ConstraintViolation.class)
                ? of(error.unwrap(ConstraintViolation.class), locale)
                : fallback(locale);
    }

    /** A method-validation error on a {@code @RequestParam}, {@code @PathVariable} or {@code @RequestHeader}. */
    public String of(ParameterValidationResult result, MessageSourceResolvable error, Locale locale) {
        try {
            return of(result.unwrap(error, ConstraintViolation.class), locale);
        } catch (IllegalArgumentException notAViolation) {
            return fallback(locale);
        }
    }

    public String of(ConstraintViolation<?> violation, Locale locale) {
        return interpolator.interpolate(violation.getMessageTemplate(), new ViolationContext(violation), locale);
    }

    private String fallback(Locale locale) {
        return messageSource.getMessage("validation.invalid.value", null, "Invalid value", locale);
    }

    private record ViolationContext(ConstraintViolation<?> violation) implements MessageInterpolator.Context {

        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return violation.getConstraintDescriptor();
        }

        @Override
        public Object getValidatedValue() {
            return violation.getInvalidValue();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new ValidationException("Cannot unwrap to " + type.getName());
        }
    }
}
