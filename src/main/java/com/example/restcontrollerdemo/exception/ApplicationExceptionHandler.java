package com.example.restcontrollerdemo.exception;

import com.example.restcontrollerdemo.filter.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Maps every uncaught exception to an RFC 9457 problem-detail response.
 *
 * <p>Client-visible messages are an allowlist, not a denylist: {@link AppException} is the only type whose message (an
 * i18n key resolved via {@link MessageSource}) reaches the client. Any other exception may carry internals (SQL,
 * paths, state), so its message is never exposed: the client receives a generic status phrase. A
 * {@link ResponseStatusException} keeps its 4xx status code, but never its text. The real message and stack trace go
 * to the log, tied to the response by the correlationId.
 */
@Component
@Order(-2)
public class ApplicationExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApplicationExceptionHandler.class);
    private static final String LIST_ELEMENT_NODE = ".<list element>";

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final ViolationMessages violationMessages;

    public ApplicationExceptionHandler(
            ObjectMapper objectMapper, MessageSource messageSource, ViolationMessages violationMessages) {
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
        this.violationMessages = violationMessages;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof CancellationException) {
            // Client disconnected mid-request — not a server error, and nothing to write to.
            return Mono.empty();
        }
        if (exchange.getResponse().isCommitted()) {
            // Headers already sent (error mid-stream): the status can no longer change.
            log.error("[{}] Error after response was committed", CorrelationIdFilter.of(exchange), ex);
            return Mono.error(ex);
        }

        String correlationId = CorrelationIdFilter.of(exchange);
        Locale locale = exchange.getLocaleContext().getLocale() != null
                ? exchange.getLocaleContext().getLocale()
                : Locale.ENGLISH;

        ProblemDetail problem = toProblem(exchange, ex, correlationId, locale);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        problem.setProperty("correlationId", correlationId);

        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(problem.getStatus()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(problem);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private ProblemDetail toProblem(ServerWebExchange exchange, Throwable ex, String correlationId, Locale locale) {
        // Business field errors that Bean Validation annotations cannot express.
        if (ex instanceof AppException.ValidationErrors validation) {
            logAppException(validation, correlationId);
            Map<String, List<String>> resolved = new LinkedHashMap<>();
            validation
                    .getFieldErrors()
                    .forEach((field, keys) -> resolved.put(
                            field,
                            keys.stream()
                                    .map(key -> messageSource.getMessage(key, null, key, locale))
                                    .toList()));
            ProblemDetail body = validation.updateAndGetBody(messageSource, locale);
            body.setProperty("errors", resolved);
            return body;
        }

        // Custom app exceptions — messages are intentionally user-safe and i18n-aware.
        if (ex instanceof AppException app) {
            logAppException(app, correlationId);
            return app.updateAndGetBody(messageSource, locale);
        }

        // @Valid @RequestBody without method validation on the same method.
        if (ex instanceof WebExchangeBindException bind) {
            Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
            bind.getFieldErrors().forEach(error -> fieldErrors
                    .computeIfAbsent(error.getField(), k -> new ArrayList<>())
                    .add(violationMessages.of(error, locale)));
            logValidation(exchange, correlationId, fieldErrors);
            return validationProblem(fieldErrors, locale);
        }

        // Constraints on @RequestParam / @PathVariable / @RequestHeader, and on @Valid @RequestBody of the same method.
        if (ex instanceof HandlerMethodValidationException methodValidation) {
            Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
            for (ParameterValidationResult result : methodValidation.getParameterValidationResults()) {
                if (result instanceof ParameterErrors body) {
                    body.getFieldErrors().forEach(error -> fieldErrors
                            .computeIfAbsent(error.getField(), k -> new ArrayList<>())
                            .add(violationMessages.of(error, locale)));
                } else {
                    String name = parameterName(result.getMethodParameter());
                    result.getResolvableErrors().forEach(error -> fieldErrors
                            .computeIfAbsent(name, k -> new ArrayList<>())
                            .add(violationMessages.of(result, error, locale)));
                }
            }
            logValidation(exchange, correlationId, fieldErrors);
            return validationProblem(fieldErrors, locale);
        }

        // Manual Validator.validate calls.
        if (ex instanceof ConstraintViolationException cve) {
            Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
            cve.getConstraintViolations().forEach(violation -> fieldErrors
                    .computeIfAbsent(
                            violation.getPropertyPath().toString().replace(LIST_ELEMENT_NODE, ""),
                            k -> new ArrayList<>())
                    .add(violationMessages.of(violation, locale)));
            logValidation(exchange, correlationId, fieldErrors);
            return validationProblem(fieldErrors, locale);
        }

        // Missing required header / query parameter: the request is malformed (400), before any validation.
        if (ex instanceof MissingRequestValueException missing) {
            log.warn("[{}] Missing {} '{}'", correlationId, missing.getLabel(), missing.getName());
            return paramProblem(missing.getName(), "param.required", locale);
        }

        // Path or query value of the wrong type (e.g. /users/abc).
        if (ex instanceof ServerWebInputException input
                && input.getCause() instanceof TypeMismatchException
                && input.getMethodParameter() != null) {
            String name = parameterName(input.getMethodParameter());
            log.warn("[{}] Type mismatch on '{}'", correlationId, name);
            return paramProblem(name, "param.type", locale);
        }

        // Body larger than spring.codec.max-in-memory-size.
        if (ex instanceof DataBufferLimitException) {
            log.warn("[{}] Request body too large: {}", correlationId, ex.getMessage());
            return ProblemDetail.forStatusAndDetail(
                    HttpStatus.PAYLOAD_TOO_LARGE, resolveStatusMessage(HttpStatus.PAYLOAD_TOO_LARGE, locale));
        }

        // Includes ServerWebInputException (malformed JSON, unknown field) and 404/405/415 from routing.
        if (ex instanceof ResponseStatusException rse) {
            if (rse.getStatusCode().is4xxClientError()) {
                log.warn("[{}] {}: {}", correlationId, rse.getClass().getSimpleName(), rse.getReason());
                return ProblemDetail.forStatusAndDetail(
                        rse.getStatusCode(), resolveStatusMessage(rse.getStatusCode(), locale));
            }
            log.error("[{}] {}: {}", correlationId, rse.getClass().getSimpleName(), rse.getReason(), rse);
            return internalError(locale);
        }

        log.error("[{}] {}: {}", correlationId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return internalError(locale);
    }

    private ProblemDetail validationProblem(Map<String, List<String>> fieldErrors, Locale locale) {
        String summary = messageSource.getMessage("error.validation_failed", null, "Validation failed", locale);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, summary);
        problem.setType(AppException.typeUri("error.validation_failed"));
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    private ProblemDetail internalError(Locale locale) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, resolveStatusMessage(HttpStatus.INTERNAL_SERVER_ERROR, locale));
    }

    /** 400 for a request that cannot be bound: missing or mistyped parameter. */
    private ProblemDetail paramProblem(String name, String messageKey, Locale locale) {
        String summary = messageSource.getMessage("error.param_invalid", null, "Invalid request parameter", locale);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, summary);
        problem.setType(AppException.typeUri("error.param_invalid"));
        problem.setProperty("errors", Map.of(name, List.of(messageSource.getMessage(messageKey, null, locale))));
        return problem;
    }

    /** The name the client used: the annotation value ({@code Idempotency-Key}), else the Java parameter name. */
    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && !param.name().isEmpty()) {
            return param.name();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.name().isEmpty()) {
            return path.name();
        }
        return parameter.getParameterName();
    }

    /** Generic phrase per status: never the exception's technical message. */
    private String resolveStatusMessage(HttpStatusCode status, Locale locale) {
        String fallback = status.is4xxClientError()
                ? "The request could not be processed."
                : "An unexpected error occurred. Please try again later.";
        return messageSource.getMessage("error." + status.value(), null, fallback, locale);
    }

    private void logAppException(AppException ex, String correlationId) {
        String label = "AppException." + ex.getClass().getSimpleName();
        if (ex.getCause() != null) {
            log.warn("[{}] {}: {}", correlationId, label, ex.getMessageKey(), ex);
        } else {
            log.warn("[{}] {}: {}", correlationId, label, ex.getMessageKey());
        }
    }

    private void logValidation(
            ServerWebExchange exchange, String correlationId, Map<String, List<String>> fieldErrors) {
        String details = fieldErrors.entrySet().stream()
                .map(e -> e.getKey() + ": " + String.join("; ", e.getValue()))
                .collect(Collectors.joining(", "));
        log.warn(
                "[{}] Validation failed on {} {}: {}",
                correlationId,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                details);
    }
}
