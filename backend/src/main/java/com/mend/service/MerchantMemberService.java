package com.mend.service;

import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.MerchantUser;
import com.mend.domain.entity.Role;
import com.mend.domain.entity.User;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.MerchantUserRepository;
import com.mend.domain.repository.RoleRepository;
import com.mend.domain.repository.UserRepository;
import com.mend.dto.AddMerchantMemberRequest;
import com.mend.dto.MerchantMemberDto;
import com.mend.dto.UpdateMemberRoleRequest;
import com.mend.exception.AuthenticationException;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class MerchantMemberService {

    private final MerchantRepository merchantRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordHasher passwordHasher;

    public MerchantMemberService(
            MerchantRepository merchantRepository,
            MerchantUserRepository merchantUserRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordHasher passwordHasher) {
        this.merchantRepository = merchantRepository;
        this.merchantUserRepository = merchantUserRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
    }

    @Transactional(readOnly = true)
    public List<MerchantMemberDto> getMerchantMembers(UUID merchantId, AuthenticatedUser currentUser) {
        validateAuthentication(currentUser);
        validateMerchantExists(merchantId);
        validateTenantAccess(currentUser, merchantId);

        List<MerchantUser> merchantUsers = merchantUserRepository.findByMerchantId(merchantId);
        List<MerchantMemberDto> result = new ArrayList<>();

        for (MerchantUser mu : merchantUsers) {
            User user = userRepository.findById(mu.getUserId()).orElse(null);
            Role role = roleRepository.findById(mu.getRoleId()).orElse(null);

            if (user != null && role != null) {
                result.add(new MerchantMemberDto(
                        user.getId(),
                        user.getEmail(),
                        user.getDisplayName(),
                        user.getStatus(),
                        role.getId(),
                        role.getName(),
                        mu.getCreatedAt()
                ));
            }
        }
        return result;
    }

    @Transactional
    public MerchantMemberDto addMerchantMember(UUID merchantId, AddMerchantMemberRequest request, AuthenticatedUser currentUser) {
        validateAuthentication(currentUser);
        validateMerchantExists(merchantId);
        validateMerchantAdminPermission(currentUser, merchantId);

        if (request == null || request.getEmail() == null || request.getRoleName() == null) {
            throw new InvalidRequestException("Email and roleName are required");
        }

        String roleName = request.getRoleName().trim().toUpperCase();
        if ("SYSTEM_ADMIN".equals(roleName)) {
            throw new InvalidRequestException("Cannot assign SYSTEM_ADMIN role via merchant APIs");
        }

        String email = request.getEmail().trim().toLowerCase();
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new InvalidRequestException("Role not found: " + request.getRoleName()));

        User targetUser;
        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            targetUser = existingUser.get();
        } else {
            if (request.getPassword() == null || request.getPassword().isBlank()) {
                throw new InvalidRequestException("Password is required when creating a new member");
            }
            String rawPassword = request.getPassword();
            String displayName = (request.getDisplayName() != null && !request.getDisplayName().isBlank())
                    ? request.getDisplayName().trim() : email;

            targetUser = new User(UUID.randomUUID(), email, passwordHasher.hashPassword(rawPassword), displayName);
            targetUser = userRepository.save(targetUser);
        }

        Optional<MerchantUser> existingMu = merchantUserRepository.findByMerchantIdAndUserId(merchantId, targetUser.getId());
        if (existingMu.isPresent()) {
            throw new InvalidRequestException("User is already a member of this merchant");
        }

        MerchantUser newMu = new MerchantUser(UUID.randomUUID(), merchantId, targetUser.getId(), role.getId());
        newMu = merchantUserRepository.save(newMu);

        return new MerchantMemberDto(
                targetUser.getId(),
                targetUser.getEmail(),
                targetUser.getDisplayName(),
                targetUser.getStatus(),
                role.getId(),
                role.getName(),
                newMu.getCreatedAt()
        );
    }

    @Transactional
    public MerchantMemberDto updateMemberRole(UUID merchantId, UUID targetUserId, UpdateMemberRoleRequest request, AuthenticatedUser currentUser) {
        validateAuthentication(currentUser);
        validateMerchantExists(merchantId);
        validateMerchantAdminPermission(currentUser, merchantId);

        if (request == null || request.getRoleName() == null) {
            throw new InvalidRequestException("roleName is required");
        }

        String newRoleName = request.getRoleName().trim().toUpperCase();
        if ("SYSTEM_ADMIN".equals(newRoleName)) {
            throw new InvalidRequestException("Cannot assign SYSTEM_ADMIN role via merchant APIs");
        }

        MerchantUser mu = merchantUserRepository.findByMerchantIdAndUserId(merchantId, targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User is not a member of this merchant"));

        Role newRole = roleRepository.findByName(newRoleName)
                .orElseThrow(() -> new InvalidRequestException("Role not found: " + request.getRoleName()));

        mu.setRoleId(newRole.getId());
        merchantUserRepository.save(mu);

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Target user not found"));

        return new MerchantMemberDto(
                targetUser.getId(),
                targetUser.getEmail(),
                targetUser.getDisplayName(),
                targetUser.getStatus(),
                newRole.getId(),
                newRole.getName(),
                mu.getCreatedAt()
        );
    }

    @Transactional
    public void removeMerchantMember(UUID merchantId, UUID targetUserId, AuthenticatedUser currentUser) {
        validateAuthentication(currentUser);
        validateMerchantExists(merchantId);
        validateMerchantAdminPermission(currentUser, merchantId);

        MerchantUser mu = merchantUserRepository.findByMerchantIdAndUserId(merchantId, targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User is not a member of this merchant"));

        merchantUserRepository.delete(mu);
    }

    private void validateAuthentication(AuthenticatedUser currentUser) {
        if (currentUser == null) {
            throw new AuthenticationException("Unauthenticated request");
        }
    }

    private void validateMerchantExists(UUID merchantId) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new ResourceNotFoundException("Merchant not found: " + merchantId);
        }
    }

    private void validateTenantAccess(AuthenticatedUser currentUser, UUID merchantId) {
        if (!currentUser.isMemberOfMerchant(merchantId)) {
            throw new TenantAccessDeniedException("Access denied to merchant " + merchantId);
        }
    }

    private void validateMerchantAdminPermission(AuthenticatedUser currentUser, UUID merchantId) {
        if (!currentUser.isSystemAdmin() && !currentUser.hasMerchantRole(merchantId, "MERCHANT_ADMIN")) {
            throw new TenantAccessDeniedException("Requires MERCHANT_ADMIN privilege for merchant " + merchantId);
        }
    }
}
