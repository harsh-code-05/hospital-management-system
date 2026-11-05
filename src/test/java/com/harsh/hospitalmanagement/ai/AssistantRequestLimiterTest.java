package com.harsh.hospitalmanagement.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

class AssistantRequestLimiterTest {
    @Test
    void sixAttemptsAreAllowedAndSeventhIsRejectedPerPatient() {
        var limiter = new AssistantRequestLimiter();
        for (int i = 0; i < 6; i++) limiter.acquire(1);
        assertThatThrownBy(() -> limiter.acquire(1)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        assertThatCode(() -> limiter.acquire(2)).doesNotThrowAnyException();
    }
}
