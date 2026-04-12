package com.tm.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.ObjectMapper;

/**
 * Configure Spring Session to use Jackson JSON serialization instead of Java serialization.
 * Java serialization (the default) is fragile and unreadable in Redis.
 * Jackson must be configured from day one — switching later invalidates all existing sessions
 * (CODING_PATTERNS.md §9).
 *
 * Spring Boot's SessionAutoConfiguration creates the RedisIndexedSessionRepository and
 * applies spring.session.timeout from application.yml automatically. Declaring
 * {@code @EnableRedisHttpSession} here would bypass that autoconfiguration and lose the timeout
 * setting, so we rely on autoconfiguration and only override the serializer via the well-known
 * bean name.
 *
 * spring.session.redis.flush-mode and spring.session.redis.namespace are applied via
 * application.yml by Spring Boot's RedisSessionProperties.
 */
@Configuration
public class RedisSessionConfig {

    /**
     * Replaces the default Java serializer with Jackson JSON.
     * Spring Session looks up this bean by the exact name {@code springSessionDefaultRedisSerializer}.
     *
     * GenericJacksonJsonRedisSerializer stores type metadata in JSON so session attributes
     * can be deserialized correctly after Redis round-trips.
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer(ObjectMapper objectMapper) {
        return new GenericJacksonJsonRedisSerializer(objectMapper);
    }
}
