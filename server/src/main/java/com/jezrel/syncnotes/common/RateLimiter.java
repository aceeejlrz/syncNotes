package com.jezrel.syncnotes.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jezrel.syncnotes.config.AuthProperties;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Per-client limits on the auth endpoints (section 10.4). Buckets live in this process's memory, so
 * a restart resets them and each server machine counts separately, which is fine for one machine.
 */
@Component
public class RateLimiter {

    private static final Duration PERIOD = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final int perMinute;
    private final TimeMeter time;
    // A bucket idle for a whole period is full again, the same as a new one, so it can be dropped.
    // The size cap stops a flood of addresses from filling the heap.
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(PERIOD)
            .maximumSize(MAX_TRACKED_CLIENTS)
            .build();

    public RateLimiter(AuthProperties props, Clock clock) {
        this.perMinute = props.rateLimitPerMinute();
        this.time = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return TimeUnit.MILLISECONDS.toNanos(clock.millis());
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }

    /** Counts one request from {@code client} against {@code scope}'s own bucket. */
    public void check(String scope, String client) {
        Bucket bucket = buckets.get(scope + "|" + client, key -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L);
            throw new RateLimitedException(Math.max(seconds, 1));
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, PERIOD))
                .withCustomTimePrecision(time)
                .build();
    }
}
