package com.mend.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * jwt.secret has no default outside the dev profile. These contexts load the real
 * application.properties / application-{profile}.properties, so they prove the shipped config.
 */
class JwtSecretConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(ObjectMapper.class)
            .withUserConfiguration(JwtService.class);

    @Test
    void defaultProfileWithoutJwtSecretFailsToStart() {
        // JWT_SECRET= neutralises any JWT_SECRET exported in the environment running the tests.
        runner.withPropertyValues("JWT_SECRET=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("jwt.secret is not configured: set JWT_SECRET");
                });
    }

    @Test
    void defaultProfileRejectsSecretShorterThan32Bytes() {
        runner.withPropertyValues("JWT_SECRET=0123456789012345678901234567890") // 31 bytes
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("jwt.secret is too short");
                });
    }

    @Test
    void defaultProfileStartsWithA32ByteSecret() {
        runner.withPropertyValues("JWT_SECRET=01234567890123456789012345678901") // 32 bytes
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JwtService.class);
                    assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
                });
    }

    @Test
    void devProfileStartsWithoutJwtSecret() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("dev");
                    assertThat(context).hasNotFailed().hasSingleBean(JwtService.class);
                });
    }

    @Test
    void constructorRejectsBlankAndShortSecrets() {
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> new JwtService("", 1000, mapper))
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> new JwtService(null, 1000, mapper))
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> new JwtService("short", 1000, mapper))
                .hasMessageContaining("too short");
    }
}
