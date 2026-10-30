package org.fsweb.visitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitCounterService {

    /** Same key the facade (formerly demo) backend used, so the existing count carries over. */
    static final String VISITS_KEY = "fs-web:visits";

    private final StringRedisTemplate redisTemplate;

    /** Atomically increments the visit counter in Redis and returns the new value. */
    public long increment() {
        Long count = redisTemplate.opsForValue().increment(VISITS_KEY);
        log.debug("Visit counter incremented to {}", count);
        return count != null ? count : 0L;
    }

    /** Returns the current visit count without changing it. */
    public long current() {
        String value = redisTemplate.opsForValue().get(VISITS_KEY);
        return value != null ? Long.parseLong(value) : 0L;
    }
}
