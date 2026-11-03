package com.jezrel.syncnotes.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jezrel.syncnotes.config.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The limit's arithmetic, with a clock the test controls, and how clients are told apart. */
class RateLimiterTest {

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-03T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final TestClock clock = new TestClock();
    final RateLimiter limiter = new RateLimiter(
            new AuthProperties("unused", Duration.ofMinutes(15), Duration.ofDays(30), 10), clock);

    void use(int times, String scope, String client) {
        for (int i = 0; i < times; i++) {
            limiter.check(scope, client);
        }
    }

    void assertLimited(String scope, String client) {
        assertThatThrownBy(() -> limiter.check(scope, client)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void tenPass_andTheEleventhIsRefused_sayingWhenToRetry() {
        use(10, "login", "1.2.3.4");
        assertThatThrownBy(() -> limiter.check("login", "1.2.3.4"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(6));    // one token per 6 s
    }

    @Test
    void waitingAsToldIsEnough_andAFullMinuteRefillsEverything() {
        use(10, "login", "1.2.3.4");
        clock.now = clock.now.plusSeconds(5);
        assertLimited("login", "1.2.3.4");
        clock.now = clock.now.plusSeconds(1);
        use(1, "login", "1.2.3.4");
        assertLimited("login", "1.2.3.4");

        clock.now = clock.now.plusSeconds(60);
        use(10, "login", "1.2.3.4");
        assertLimited("login", "1.2.3.4");
    }

    @Test
    void clientsAndEndpoints_countSeparately() {
        use(10, "login", "1.2.3.4");
        assertLimited("login", "1.2.3.4");
        use(10, "login", "5.6.7.8");
        use(10, "refresh", "1.2.3.4");
    }

    // ---- ClientIp ------------------------------------------------------------------------------

    static String clientOf(String remoteAddr, String flyClientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (flyClientIp != null) {
            request.addHeader("Fly-Client-IP", flyClientIp);
        }
        return ClientIp.of(request);
    }

    @Test
    void theFlyHeaderIsPreferred_andABlankOneIsIgnored() {
        assertThat(clientOf("10.0.0.1", "203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(clientOf("10.0.0.1", null)).isEqualTo("10.0.0.1");
        assertThat(clientOf("10.0.0.1", "  ")).isEqualTo("10.0.0.1");
    }

    @Test
    void xForwardedFor_isIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(ClientIp.of(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void ipv6_countsPerSlash64() {
        String one = clientOf("10.0.0.1", "2001:db8:1:2:aaaa::1");
        assertThat(clientOf("10.0.0.1", "2001:db8:1:2:bbbb::2")).isEqualTo(one);          // same /64
        assertThat(clientOf("10.0.0.1", "2001:db8:1:3:aaaa::1")).isNotEqualTo(one);       // another /64
        assertThat(clientOf("10.0.0.1", "2001:0db8:0001:0002:0000:0000:0000:0009")).isEqualTo(one); // long form
    }

    @Test
    void anIpv4MappedAddress_isThatIpv4Client() {
        assertThat(clientOf("10.0.0.1", "::ffff:203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void aMalformedValue_isOnlyAKey_andNeverLookedUp() {
        assertThat(clientOf("10.0.0.1", "not-an-ip.example.com")).isEqualTo("not-an-ip.example.com");
        assertThat(clientOf("10.0.0.1", "zz:zz")).isEqualTo("zz:zz");
        assertThat(clientOf("10.0.0.1", "1:2:3")).isEqualTo("1:2:3");
    }
}
