package com.example.ratelimiter.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test configuration that starts a Redis container via Testcontainers and provides
 * a {@link Primary} connection factory so the application's {@link StringRedisTemplate}
 * binds to the container instead of localhost.
 */
@TestConfiguration
public class TestRedisConfig {

    private static final GenericContainer<?> redisContainer;

    static {
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redisContainer.start();
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        String host = redisContainer.getHost();
        Integer port = redisContainer.getMappedPort(6379);
        return new LettuceConnectionFactory(host, port);
    }
}
