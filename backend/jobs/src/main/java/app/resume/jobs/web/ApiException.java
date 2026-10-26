package app.resume.jobs.web;

import org.springframework.http.HttpStatus;

/** A request problem that maps to a status and a contract error body. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ApiProblem problem;

    public ApiException(HttpStatus status, ApiProblem problem) {
        super(problem.message());
        this.status = status;
        this.problem = problem;
    }

    public HttpStatus status() {
        return status;
    }

    public ApiProblem problem() {
        return problem;
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiProblem.validation(field, message));
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ApiProblem.of("not_found", "Not found"));
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ApiProblem.of("unauthorized", "Sign in required"));
    }
}
