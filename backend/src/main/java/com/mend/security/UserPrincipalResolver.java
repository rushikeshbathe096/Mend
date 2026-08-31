package com.mend.security;

import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.MerchantUser;
import com.mend.domain.entity.Role;
import com.mend.domain.entity.User;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.MerchantUserRepository;
import com.mend.domain.repository.RoleRepository;
import com.mend.domain.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class UserPrincipalResolver {

    private final UserRepository userRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final MerchantRepository merchantRepository;
    private final RoleRepository roleRepository;

    public UserPrincipalResolver(
            UserRepository userRepository,
            MerchantUserRepository merchantUserRepository,
            MerchantRepository merchantRepository,
            RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.merchantUserRepository = merchantUserRepository;
        this.merchantRepository = merchantRepository;
        this.roleRepository = roleRepository;
    }

    public AuthenticatedUser resolveUser(UUID userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return null;
        }

        User user = userOpt.get();
        List<MerchantUser> merchantUsers = merchantUserRepository.findByUserId(userId);

        List<AuthenticatedUser.MerchantMembershipInfo> memberships = new ArrayList<>();

        for (MerchantUser mu : merchantUsers) {
            String merchantName = merchantRepository.findById(mu.getMerchantId())
                    .map(Merchant::getName)
                    .orElse("Unknown Merchant");
            String roleName = roleRepository.findById(mu.getRoleId())
                    .map(Role::getName)
                    .orElse("UNKNOWN");

            memberships.add(new AuthenticatedUser.MerchantMembershipInfo(
                    mu.getMerchantId(),
                    merchantName,
                    mu.getRoleId(),
                    roleName
            ));
        }

        return new AuthenticatedUser(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                memberships
        );
    }
}
