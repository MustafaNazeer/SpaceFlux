package io.github.mustafanazeer.spaceflux.query.web;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 9457 problem body with a generic title and detail and the request's correlation ID. Stack
 * traces and SQL go to the log with that ID and never into a response (SEC-API-08).
 */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    /** A request this API refuses, with a detail written by the API, never taken from the request. */
    public static class Refused extends RuntimeException {

        private final HttpStatus status;

        public Refused(HttpStatus status, String detail) {
            super(detail, null, false, false);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    @ExceptionHandler(Refused.class)
    ResponseEntity<ProblemDetail> refused(Refused e, HttpServletRequest request) {
        return body(ProblemDetail.forStatusAndDetail(e.status, e.getMessage()), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        LOG.error("request failed, correlation_id={}", CorrelationIdFilter.of(request), e);
        return body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The request failed."),
                request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        HttpServletRequest servlet = ((ServletWebRequest) request).getRequest();
        if (status.is5xxServerError()) {
            LOG.error("request failed, correlation_id={}", CorrelationIdFilter.of(servlet), e);
        }
        // Spring's own detail can echo a request value, so a generic one replaces it.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, HttpStatus.valueOf(status.value())
                .getReasonPhrase() + ".");
        problem.setProperty("correlation_id", CorrelationIdFilter.of(servlet));
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private static ResponseEntity<ProblemDetail> body(ProblemDetail problem, HttpServletRequest request) {
        problem.setProperty("correlation_id", CorrelationIdFilter.of(request));
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
