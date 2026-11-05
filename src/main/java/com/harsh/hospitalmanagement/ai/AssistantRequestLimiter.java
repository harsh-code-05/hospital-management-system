package com.harsh.hospitalmanagement.ai;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@Component
class AssistantRequestLimiter {
    private final Map<Long, Window> windows = new HashMap<>();

    synchronized void acquire(long userId) {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        Window window = windows.get(userId);
        if (window == null) {
            if (windows.size() >= 10000) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The assistant is busy. Please try again later.");
            }
            window = new Window(now + 60000);
            windows.put(userId, window);
        }
        if (window.attempts >= 6) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait a minute before sending another message.");
        }
        window.attempts++;
    }

    private static class Window {
        final long expiresAt;
        int attempts;

        Window(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
