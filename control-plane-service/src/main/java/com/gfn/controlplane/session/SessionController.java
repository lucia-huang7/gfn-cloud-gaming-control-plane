package com.gfn.controlplane.session;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> createSession(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateSessionRequest request) {
        return ResponseEntity.accepted().body(sessionService.createSession(idempotencyKey, request));
    }

    @GetMapping("/{sessionId}")
    public SessionResponse getSession(@PathVariable String sessionId) {
        return sessionService.getSession(sessionId);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> terminate(@PathVariable String sessionId) {
        sessionService.terminateSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}

