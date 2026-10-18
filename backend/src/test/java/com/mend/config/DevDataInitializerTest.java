package com.mend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class DevDataInitializerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DevDataInitializer.class);

    @Test
    public void whenMendDevSeedIsTrue_beanIsLoaded() {
        contextRunner
                .withPropertyValues("mend.dev.seed=true")
                .withBean(com.mend.domain.repository.UserRepository.class, () -> mock(com.mend.domain.repository.UserRepository.class))
                .withBean(com.mend.domain.repository.MerchantRepository.class, () -> mock(com.mend.domain.repository.MerchantRepository.class))
                .withBean(com.mend.domain.repository.MerchantUserRepository.class, () -> mock(com.mend.domain.repository.MerchantUserRepository.class))
                .withBean(com.mend.domain.repository.RoleRepository.class, () -> mock(com.mend.domain.repository.RoleRepository.class))
                .withBean(com.mend.domain.repository.MerchantConfigRepository.class, () -> mock(com.mend.domain.repository.MerchantConfigRepository.class))
                .withBean(com.mend.domain.repository.WebhookEventRepository.class, () -> mock(com.mend.domain.repository.WebhookEventRepository.class))
                .withBean(com.mend.domain.repository.CampaignRepository.class, () -> mock(com.mend.domain.repository.CampaignRepository.class))
                .withBean(com.mend.domain.repository.ClassificationResultRepository.class, () -> mock(com.mend.domain.repository.ClassificationResultRepository.class))
                .withBean(com.mend.domain.repository.RecoveryDecisionRepository.class, () -> mock(com.mend.domain.repository.RecoveryDecisionRepository.class))
                .withBean(com.mend.domain.repository.ComplianceDecisionRepository.class, () -> mock(com.mend.domain.repository.ComplianceDecisionRepository.class))
                .withBean(com.mend.domain.repository.ActionIntentRepository.class, () -> mock(com.mend.domain.repository.ActionIntentRepository.class))
                .withBean(com.mend.domain.repository.ReviewQueueRepository.class, () -> mock(com.mend.domain.repository.ReviewQueueRepository.class))
                .withBean(com.mend.domain.repository.AuditLogRepository.class, () -> mock(com.mend.domain.repository.AuditLogRepository.class))
                .withBean(com.mend.security.PasswordHasher.class, () -> mock(com.mend.security.PasswordHasher.class))
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
