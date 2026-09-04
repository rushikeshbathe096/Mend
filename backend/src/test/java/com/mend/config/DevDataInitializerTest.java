package com.mend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

public class DevDataInitializerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DevDataInitializer.class);

    @Test
    public void whenMendDevSeedIsTrue_beanIsLoaded() {
        contextRunner
                .withPropertyValues("mend.dev.seed=true")
                .withBean(com.mend.domain.repository.UserRepository.class, () -> org.mockito.Mockito.mock(com.mend.domain.repository.UserRepository.class))
                .withBean(com.mend.domain.repository.MerchantRepository.class, () -> org.mockito.Mockito.mock(com.mend.domain.repository.MerchantRepository.class))
                .withBean(com.mend.domain.repository.MerchantUserRepository.class, () -> org.mockito.Mockito.mock(com.mend.domain.repository.MerchantUserRepository.class))
                .withBean(com.mend.domain.repository.RoleRepository.class, () -> org.mockito.Mockito.mock(com.mend.domain.repository.RoleRepository.class))
                .withBean(com.mend.domain.repository.MerchantConfigRepository.class, () -> org.mockito.Mockito.mock(com.mend.domain.repository.MerchantConfigRepository.class))
                .withBean(com.mend.security.PasswordHasher.class, () -> org.mockito.Mockito.mock(com.mend.security.PasswordHasher.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(DevDataInitializer.class);
                });
    }

    @Test
    public void whenMendDevSeedIsFalseOrUnset_beanIsNotLoaded() {
        contextRunner
                .withPropertyValues("mend.dev.seed=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DevDataInitializer.class);
                });

        contextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DevDataInitializer.class);
                });
    }
}
