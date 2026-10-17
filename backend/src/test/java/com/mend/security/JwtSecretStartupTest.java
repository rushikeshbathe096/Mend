package com.mend.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cleanup (security): there is no default JWT secret outside the dev profile. The runner loads the real
 * application*.properties, but with no OS environment / system properties, so an exported JWT_SECRET on the
 * machine running the tests cannot hide a missing secret. Complements {@link JwtSecretConfigurationTest}, whose
 * dev-profile case cannot neutralise an exported JWT_SECRET (an empty override resolves to empty, not to the dev
 * default). Reconciled from branch cleanup/pre-m3-fixes; assertions follow this branch's JwtService messages.
 */
class JwtSecretStartupTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";

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
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(JwtService.class);

    @Test
    void defaultProfileWithoutJwtSecretFailsToStart() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.secret is not configured: set JWT_SECRET");
        });
    }

    @Test
    void defaultProfileRejectsSecretShorterThan32Bytes() {
        runner.withPropertyValues("JWT_SECRET=" + SECRET_32.substring(1)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jwt.secret is too short");
        });
    }

    @Test
    void defaultProfileStartsWithA32ByteJwtSecret() {
        runner.withPropertyValues("JWT_SECRET=" + SECRET_32)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtService.class));
    }

    @Test
    void devProfileStartsWithoutJwtSecret() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtService.class));
    }
}
