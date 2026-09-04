package com.mend.config;

import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.entity.MerchantUser;
import com.mend.domain.entity.Role;
import com.mend.domain.entity.User;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.MerchantUserRepository;
import com.mend.domain.repository.RoleRepository;
import com.mend.domain.repository.UserRepository;
import com.mend.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "mend.dev.seed", havingValue = "true")
public class DevDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final RoleRepository roleRepository;
    private final MerchantConfigRepository merchantConfigRepository;
    private final PasswordHasher passwordHasher;

    public DevDataInitializer(
            UserRepository userRepository,
            MerchantRepository merchantRepository,
            MerchantUserRepository merchantUserRepository,
            RoleRepository roleRepository,
            MerchantConfigRepository merchantConfigRepository,
            PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.merchantRepository = merchantRepository;
        this.merchantUserRepository = merchantUserRepository;
        this.roleRepository = roleRepository;
        this.merchantConfigRepository = merchantConfigRepository;
        this.passwordHasher = passwordHasher;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Optional<Role> adminRoleOpt = roleRepository.findByName("MERCHANT_ADMIN");
        if (adminRoleOpt.isEmpty()) {
            log.warn("MERCHANT_ADMIN role not found. Skipping dev seed initialization.");
            return;
        }

        Role adminRole = adminRoleOpt.get();

        seedAccount("admin@testmerchant.com", "AdminPass123!", "Test Merchant Admin", "Test Merchant", "test_merchant", adminRole);
        seedAccount("admin@acme.com", "AdminPass123!", "Acme Admin", "Acme Enterprise", "acme_enterprise", adminRole);
    }

    private void seedAccount(String email, String rawPassword, String displayName, String merchantName, String externalRef, Role adminRole) {
        String normalizedEmail = email.trim().toLowerCase();
        String passwordHash = passwordHasher.hashPassword(rawPassword);

        Optional<User> existingUserOpt = userRepository.findByEmail(normalizedEmail);
        User user;
        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            user.setPasswordHash(passwordHash);
            user.setDisplayName(displayName);
            user = userRepository.save(user);
            log.info("Updated existing development user password hash: {}", normalizedEmail);
        } else {
            log.info("Seeding development merchant user: {}", normalizedEmail);
            user = new User(UUID.randomUUID(), normalizedEmail, passwordHash, displayName);
            user = userRepository.save(user);
        }

        Merchant merchant = merchantRepository.findByExternalReference(externalRef)
                .orElseGet(() -> {
                    Merchant m = new Merchant(UUID.randomUUID(), merchantName);
                    m.setExternalReference(externalRef);
                    return merchantRepository.save(m);
                });

        if (merchantUserRepository.findByUserId(user.getId()).isEmpty()) {
            MerchantUser merchantUser = new MerchantUser(UUID.randomUUID(), merchant.getId(), user.getId(), adminRole.getId());
            merchantUserRepository.save(merchantUser);
        }

        if (merchantConfigRepository.findByMerchantId(merchant.getId()).isEmpty()) {
            MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchant.getId());
            config.setMaxAttempts(3);
            config.setMaxContactAttempts(3);
            config.setContactWindowHours(24);
            merchantConfigRepository.save(config);
        }

        log.info("Successfully configured merchant '{}' with admin user '{}'", merchantName, normalizedEmail);
    }
}
