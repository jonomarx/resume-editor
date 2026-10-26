package app.resume.jobs.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the job pipeline's controller problems to the contract's error shape. */
@RestControllerAdvice(basePackages = "app.resume.jobs")
public class JobsApiAdvice {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiProblem> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(e.problem());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiProblem> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiProblem.of("validation_failed", "Request body is missing or malformed"));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiProblem> badParameter(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiProblem.validation(e.getName(), "has an invalid value"));
    }
}
