package com.harsh.hospitalmanagement.security;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Fixed windows per connection source. Reservations are made before password verification. */
public final class LoginRateLimiter {
    private static final int ATTEMPTS_PER_WINDOW = 10;
    private static final int MAX_SOURCES = 10_000;
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    private final Map<String, Window> windows = new HashMap<>();
    private final LongSupplier nanoTime;
    private long lastCleanup;

    public LoginRateLimiter() {
        this(System::nanoTime);
    }

    LoginRateLimiter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.lastCleanup = nanoTime.getAsLong();
    }

    public synchronized long reserve(String source) {
        long now = nanoTime.getAsLong();
        if (now - lastCleanup >= WINDOW_NANOS) {
            windows.values().removeIf(window -> now - window.started >= WINDOW_NANOS);
            lastCleanup = now;
        }

        Window window = windows.get(source);
        if (window != null && now - window.started >= WINDOW_NANOS) {
            windows.remove(source);
            window = null;
        }
        if (window == null) {
            if (windows.size() >= MAX_SOURCES) {
                return Duration.ofNanos(WINDOW_NANOS).toSeconds();
            }
            window = new Window(now);
            windows.put(source, window);
        }
        if (window.attempts >= ATTEMPTS_PER_WINDOW) {
            return Duration.ofNanos(WINDOW_NANOS - (now - window.started)).toSeconds() + 1;
        }
        window.attempts++;
        return 0;
    }

    private static final class Window {
        private final long started;
        private int attempts;

        private Window(long started) {
            this.started = started;
        }
    }
}
