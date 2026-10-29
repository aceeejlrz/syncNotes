package com.jezrel.syncnotes.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One injectable clock, so services and tests agree on "now". */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
