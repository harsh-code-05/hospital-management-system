package com.harsh.hospitalmanagement.ai;

import com.harsh.hospitalmanagement.security.LocalAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/patient/assistant/sessions")
public class PatientAssistantController {
    private final PatientAssistantService assistant;

    public PatientAssistantController(PatientAssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssistantDtos.Session create(@Valid @RequestBody AssistantDtos.CreateSession request, Authentication auth) {
        return assistant.create(LocalAccount.require(auth), request);
    }

    @GetMapping
    public List<AssistantDtos.Session> list(@RequestParam(defaultValue = "0") @Min(0) int page, Authentication auth) {
        return assistant.list(LocalAccount.require(auth), page);
    }

    @GetMapping("/{sessionId}/messages")
    public List<AssistantDtos.Message> history(@PathVariable @Min(1) Long sessionId,
            @RequestParam(defaultValue = "9223372036854775807") @Min(1) Long before, Authentication auth) {
        return assistant.history(LocalAccount.require(auth), sessionId, before);
    }

    @PostMapping("/{sessionId}/messages")
    public AssistantDtos.Turn send(@PathVariable @Min(1) Long sessionId, @Valid @RequestBody AssistantDtos.SendMessage request,
                                   Authentication auth) {
        return assistant.send(LocalAccount.require(auth), sessionId, request);
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Min(1) Long sessionId, Authentication auth) {
        assistant.delete(LocalAccount.require(auth), sessionId);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<Map<String, Object>> busy() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", 409, "message", "This conversation is busy. Retry the same message shortly."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> requestFailure(ResponseStatusException error) {
        var response = ResponseEntity.status(error.getStatusCode());
        if (error.getStatusCode().value() == 429) {
            response.header("Retry-After", "60");
        }
        return response.body(Map.of("status", error.getStatusCode().value(), "message", error.getReason() == null
                ? "Unable to process this request" : error.getReason()));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> persistenceConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", 409, "message", "Unable to save this conversation. Retry the same message."));
    }

}
