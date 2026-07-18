package it.getyourpc.controller.common;

import it.getyourpc.model.common.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> responseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiError.of(exception.getReason() == null ? "Richiesta non valida" : exception.getReason()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            MissingServletRequestPartException.class})
    ResponseEntity<ApiError> validation(Exception exception) {
        String message;
        if (exception instanceof MethodArgumentNotValidException invalid) {
            message = invalid.getBindingResult().getFieldErrors().stream()
                    .findFirst().map(error -> error.getDefaultMessage()).orElse("Dati non validi");
        } else if (exception instanceof ConstraintViolationException invalid) {
            message = invalid.getConstraintViolations().stream().findFirst()
                    .map(violation -> violation.getMessage()).orElse("Dati non validi");
        } else if (exception instanceof MissingServletRequestPartException missing) {
            message = "Parte obbligatoria mancante: " + missing.getRequestPartName();
        } else {
            message = "Dati non validi";
        }
        return ResponseEntity.badRequest().body(ApiError.of(message));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("Parametro obbligatorio mancante: " + exception.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("Valore non valido per il parametro: " + exception.getName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody() {
        return ResponseEntity.badRequest().body(ApiError.of("Corpo della richiesta non valido"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMediaType() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiError.of("Formato della richiesta non supportato"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of("Metodo HTTP non supportato"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("Risorsa non trovata"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("Le immagini superano la dimensione massima consentita"));
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiError> databaseUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("Database temporaneamente non disponibile"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("La risorsa esiste già"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> invalidDatabaseData() {
        return ResponseEntity.badRequest().body(ApiError.of("Dati non compatibili con il database"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        LOGGER.error("Errore API inatteso", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("Errore interno del server"));
    }
}
