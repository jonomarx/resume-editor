package app.resume.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps request problems to the contract's error shape. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        List<ApiError.FieldProblem> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldProblem(f.getField(), f.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("validation_failed", "Request failed validation", details));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badParameter(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("validation_failed",
                "Request failed validation", List.of(new ApiError.FieldProblem(e.getName(), "has an invalid value"))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("validation_failed", "Request body is missing or malformed"));
    }
}
