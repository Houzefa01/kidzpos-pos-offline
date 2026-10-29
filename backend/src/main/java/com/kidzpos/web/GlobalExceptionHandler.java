package com.kidzpos.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * I1 : format d'erreur unifié {error: "..."} pour aligner avec apiClient.ts (front).
 * Chaque handler retourne un code HTTP cohérent avec la sémantique métier.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + (f.getDefaultMessage() == null ? "invalide" : f.getDefaultMessage()))
                .orElse("Données invalides");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler({NoSuchElementException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, String>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Ressource introuvable"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleIntegrity(DataIntegrityViolationException e) {
        log.warn("DB integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Conflit de données"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "Requête invalide" : e.getMessage()));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthenticationCredentialsNotFoundException.class})
    public ResponseEntity<Map<String, String>> handleAccessDenied(Exception e) {
        // Spring Security gère déjà ces cas via le filter chain, mais ce handler couvre
        // les cas où l'exception remonte jusqu'au contrôleur (rare).
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Accès refusé"));
    }

    /**
     * Broken pipe / client abort — typique des SSE quand un client ferme l'onglet.
     * On ne peut PAS écrire de body JSON ici car la réponse est en text/event-stream
     * (déclencherait HttpMessageNotWritableException). Retour void = laisse Spring
     * fermer proprement la réponse async.
     */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.OK)
    public void handleClientDisconnect(IOException e) {
        log.debug("Client disconnect (broken pipe / aborted): {}", e.getMessage());
    }

    /**
     * Timeout async (typiquement SSE qui dépasse le timeout Tomcat). Le client
     * EventSource reconnecte automatiquement — pas une erreur applicative.
     * Idem que broken pipe : pas de body JSON possible (réponse text/event-stream).
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    @ResponseStatus(HttpStatus.OK)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.debug("Async request timeout (SSE keepalive expected): {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Erreur serveur"));
    }
}
