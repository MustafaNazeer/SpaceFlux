package io.github.mustafanazeer.spaceflux.query.web;

import java.net.URI;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The container's error path, used for what Spring MVC never sees: an exception thrown in a filter, a method the
 * container refuses, a direct request for /error. It answers with the same generic problem body as {@link ApiErrors},
 * keeping the status the container chose, so no error reaches a client in another format (SEC-API-08).
 */
@RestController
class ApiErrorController implements ErrorController {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorController.class);

    @RequestMapping("/error")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request, HttpServletResponse response) {
        String id = CorrelationIdFilter.ensure(request, response);
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer c && HttpStatus.resolve(c) != null ? HttpStatus.valueOf(c)
                : code == null ? HttpStatus.NOT_FOUND : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            LOG.error("request failed, correlation_id={}", id,
                    (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase() + ".");
        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        try {
            problem.setInstance(URI.create(path instanceof String p ? p : request.getRequestURI()));
        } catch (IllegalArgumentException e) {
            // A path that is not a URI is left out rather than making the error path fail.
        }
        problem.setProperty("correlation_id", id);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
