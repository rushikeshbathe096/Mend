package com.mend.service;

import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.MerchantUser;
import com.mend.domain.entity.Role;
import com.mend.domain.entity.User;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.MerchantUserRepository;
import com.mend.domain.repository.RoleRepository;
import com.mend.domain.repository.UserRepository;
import com.mend.dto.*;
import com.mend.exception.AuthenticationException;
import com.mend.exception.InvalidRequestException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.JwtService;
import com.mend.security.PasswordHasher;
import com.mend.security.UserPrincipalResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final RoleRepository roleRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final UserPrincipalResolver userPrincipalResolver;

    @Value("${jwt.expiration:86400000}")
    private long expirationMs;

    public AuthService(
            UserRepository userRepository,
            MerchantRepository merchantRepository,
            MerchantUserRepository merchantUserRepository,
            RoleRepository roleRepository,
            PasswordHasher passwordHasher,
            JwtService jwtService,
            UserPrincipalResolver userPrincipalResolver) {
        this.userRepository = userRepository;
        this.merchantRepository = merchantRepository;
        this.merchantUserRepository = merchantUserRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.userPrincipalResolver = userPrincipalResolver;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        if (request == null || request.getEmail() == null || request.getPassword() == null) {
            throw new InvalidRequestException("Email and password are required");
        }

        User user = userRepository.findByEmail(request.getEmail().trim().toLowerCase())
                .orElseThrow(() -> new AuthenticationException("Invalid email or password"));

        if (!passwordHasher.verifyPassword(request.getPassword(), user.getPasswordHash())) {
            throw new AuthenticationException("Invalid email or password");
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new AuthenticationException("User account is inactive");
        }

        AuthenticatedUser authenticatedUser = userPrincipalResolver.resolveUser(user.getId());

        List<String> roles = authenticatedUser.getMemberships().stream()
                .map(AuthenticatedUser.MerchantMembershipInfo::getRoleName)
                .distinct()
                .collect(Collectors.toList());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), roles);

        List<MerchantMembershipDto> membershipDtos = authenticatedUser.getMemberships().stream()
                .map(m -> new MerchantMembershipDto(m.getMerchantId(), m.getMerchantName(), m.getRoleId(), m.getRoleName()))
                .collect(Collectors.toList());

        UserDto userDto = new UserDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt(),
                membershipDtos
        );

        return new LoginResponse(token, expirationMs / 1000, userDto);
    }

    @Transactional(readOnly = true)
    public UserDto getCurrentUser(AuthenticatedUser currentUser) {
        if (currentUser == null) {
            throw new AuthenticationException("Unauthenticated user");
        }

        User user = userRepository.findById(currentUser.getUserId())
                .orElseThrow(() -> new AuthenticationException("User not found"));

        AuthenticatedUser resolved = userPrincipalResolver.resolveUser(user.getId());

        List<MerchantMembershipDto> membershipDtos = resolved.getMemberships().stream()
                .map(m -> new MerchantMembershipDto(m.getMerchantId(), m.getMerchantName(), m.getRoleId(), m.getRoleName()))
                .collect(Collectors.toList());

        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt(),
                membershipDtos
        );
    }

    @Transactional
    public BootstrapResponse bootstrap(BootstrapRequest request) {
        if (request == null || request.getMerchantName() == null || request.getAdminEmail() == null || request.getAdminPassword() == null) {
            throw new InvalidRequestException("Merchant name, admin email, and admin password are required for bootstrap");
        }

        String email = request.getAdminEmail().trim().toLowerCase();

        // 1. Create or fetch Merchant
        Merchant merchant = new Merchant(UUID.randomUUID(), request.getMerchantName().trim());
        merchant = merchantRepository.save(merchant);

        // 2. Fetch MERCHANT_ADMIN role
        Role adminRole = roleRepository.findByName("MERCHANT_ADMIN")
                .orElseThrow(() -> new InvalidRequestException("System role MERCHANT_ADMIN not found"));

        // 3. Create or fetch User
        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User adminUser;
        if (existingUserOpt.isPresent()) {
            adminUser = existingUserOpt.get();
        } else {
            String passwordHash = passwordHasher.hashPassword(request.getAdminPassword());
            String displayName = request.getAdminDisplayName() != null ? request.getAdminDisplayName().trim() : "Admin User";
            adminUser = new User(UUID.randomUUID(), email, passwordHash, displayName);
            adminUser = userRepository.save(adminUser);
        }

        // 4. Check if membership exists, if not create
        Optional<MerchantUser> existingMu = merchantUserRepository.findByMerchantIdAndUserId(merchant.getId(), adminUser.getId());
        if (existingMu.isEmpty()) {
            MerchantUser merchantUser = new MerchantUser(UUID.randomUUID(), merchant.getId(), adminUser.getId(), adminRole.getId());
            merchantUserRepository.save(merchantUser);
        }

        return new BootstrapResponse(
                merchant.getId(),
                merchant.getName(),
                adminUser.getId(),
                adminUser.getEmail(),
                adminRole.getName()
        );
    }
}
