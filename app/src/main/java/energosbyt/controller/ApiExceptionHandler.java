package energosbyt.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;

import energosbyt.exception.DatabaseException;
import energosbyt.exception.DomainException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Message> domainError(DomainException error) {
        return ResponseEntity.status(error.reason() == DomainException.Reason.NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(new Message(error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Message> invalidBody(HttpMessageNotReadableException error) {
        if (error.getCause() instanceof DatabindException databind && !databind.getPath().isEmpty()) {
            String field = databind.getPath().get(0).getPropertyName();
            if (field != null) {
                return domainError(DomainException.field(field));
            }
        }
        return domainError(DomainException.invalidInput("invalid request body"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Message> notFound() {
        return domainError(DomainException.notFound());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Message> methodNotAllowed(HttpRequestMethodNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(error.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Message("Method not allowed"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Message> unsupportedMediaType(HttpMediaTypeNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(error.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Message("Unsupported media type"));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Message> notAcceptable(HttpMediaTypeNotAcceptableException error) {
        // The lab requires JSON errors even when the requested representation is unavailable.
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .headers(error.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Message("Not acceptable"));
    }

    @ExceptionHandler(DatabaseException.class)
    ResponseEntity<Message> databaseFailure(DatabaseException error) {
        LOG.error("ClickHouse request failed", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body(new Message("Internal server error"));
    }

    record Message(String message) {}
}
