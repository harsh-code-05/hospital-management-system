package com.harsh.hospitalmanagement.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {
    @Test
    void concurrentAttemptsUseOneSourceBudgetAndItRecoversAfterTheWindow() {
        AtomicLong time = new AtomicLong();
        LoginRateLimiter limiter = new LoginRateLimiter(time::get);

        long admitted = IntStream.range(0, 20).parallel()
                .filter(ignored -> limiter.reserve("198.51.100.42") == 0).count();

        assertThat(admitted).isEqualTo(10);
        assertThat(limiter.reserve("198.51.100.43")).isZero();
        time.addAndGet(Duration.ofMinutes(1).toNanos());
        assertThat(limiter.reserve("198.51.100.42")).isZero();
    }
}
