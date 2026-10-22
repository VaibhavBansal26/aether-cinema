package com.aether.api;

import java.util.Map;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Uniform errors without leaking SQL, authorization headers or upstream provider responses. */
@RestControllerAdvice
public class ErrorHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ErrorHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String,String>> api(ApiException e) { return ResponseEntity.status(e.status()).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,String>> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","Invalid request")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String,String>> conflict(Exception e) { if(e instanceof DataIntegrityViolationException error&&error.getMostSpecificCause().getMessage()!=null&&error.getMostSpecificCause().getMessage().contains("titles_episode_number"))return ResponseEntity.status(409).body(Map.of("error","This season and episode already exist. Select that episode or choose another number."));return ResponseEntity.status(409).body(Map.of("error","Related record missing or conflicting")); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missing(Exception e){return ResponseEntity.status(404).body(Map.of("error","Endpoint not found"));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,String>> unexpected(Exception e) {
        LOG.error("API request failed: {}",e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("error","Request failed"));
    }
}
