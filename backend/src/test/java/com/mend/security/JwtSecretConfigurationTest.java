package com.mend.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * jwt.secret has no default outside the dev profile. These contexts load the real
 * application.properties / application-{profile}.properties, so they prove the shipped config.
 * Bug sweep item 6b: the environment has no OS environment / system property sources (as in JwtSecretStartupTest), so a
 * JWT_SECRET exported in the shell running the tests cannot make a "fails to start" case start.
 */
class JwtSecretConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner(() -> {
                AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
                context.setEnvironment(new StandardEnvironment() {
                    @Override
                    protected void customizePropertySources(MutablePropertySources propertySources) {
                        // no systemEnvironment / systemProperties
                    }
                });
                return context;
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(ObjectMapper.class)
            .withUserConfiguration(JwtService.class);

    @Test
    void defaultProfileWithoutJwtSecretFailsToStart() {
        // An empty JWT_SECRET, as an explicit property (the OS environment is not a source here, see the class comment).
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
