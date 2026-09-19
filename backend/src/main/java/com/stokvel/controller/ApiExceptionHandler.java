package com.stokvel.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The services refuse things for two different reasons, and the difference matters
 * to a caller. "That amount is not money" is the request's fault; "every cycle has
 * already paid out" is nothing to do with the request, it is the state the stokvel
 * is in. Left unmapped, both arrive as a 500 with a stack trace in the body, which
 * tells a client nothing and tells a demo audience something worse.
 *
 * Transport concern, so it is deliberately not test-driven — but it is the one place
 * a business-rule refusal becomes an HTTP answer, so the mapping lives here rather
 * than being repeated in every controller.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Bad input: a negative amount, a member id that does not exist, a blank name. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception);
    }

    /** A refusal about state: the stokvel already exists, the rotation has closed. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException exception) {
        return problem(HttpStatus.CONFLICT, exception);
    }

    /**
     * The services' messages are written to be read by a person — they explain the
     * rule, not the stack — so the message is the response body.
     */
    private static ProblemDetail problem(HttpStatus status, RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    }
}
