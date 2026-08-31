package com.mend.integration;

import com.mend.dto.*;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.UserPrincipalResolver;
import com.mend.service.AuthService;
import com.mend.service.MerchantMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class RbacAndMultiTenancyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private MerchantMemberService merchantMemberService;

    @Autowired
    private UserPrincipalResolver userPrincipalResolver;

    private BootstrapResponse merchantA;
    private BootstrapResponse merchantB;
    private AuthenticatedUser merchantAAdminUser;
    private AuthenticatedUser merchantBAdminUser;

    @BeforeEach
    public void setUp() {
        merchantA = authService.bootstrap(new BootstrapRequest("Tenant Alpha", "admin@alpha.com", "Pass123!", "Alpha Admin"));
        merchantB = authService.bootstrap(new BootstrapRequest("Tenant Beta", "admin@beta.com", "Pass123!", "Beta Admin"));

        merchantAAdminUser = userPrincipalResolver.resolveUser(merchantA.getUserId());
        merchantBAdminUser = userPrincipalResolver.resolveUser(merchantB.getUserId());
    }

    @Test
    public void testMerchantAdminCanGetMembers() {
        List<MerchantMemberDto> members = merchantMemberService.getMerchantMembers(merchantA.getMerchantId(), merchantAAdminUser);
        assertNotNull(members);
        assertEquals(1, members.size());
        assertEquals("admin@alpha.com", members.get(0).getEmail());
        assertEquals("MERCHANT_ADMIN", members.get(0).getRoleName());
    }

    @Test
    public void testMerchantAdminCanAddMember() {
        AddMerchantMemberRequest req = new AddMerchantMemberRequest(
                "reviewer@alpha.com",
                "ReviewerPass123!",
                "Alpha Reviewer",
                "REVIEWER"
        );

        MerchantMemberDto added = merchantMemberService.addMerchantMember(merchantA.getMerchantId(), req, merchantAAdminUser);
        assertNotNull(added);
        assertEquals("reviewer@alpha.com", added.getEmail());
        assertEquals("REVIEWER", added.getRoleName());

        List<MerchantMemberDto> members = merchantMemberService.getMerchantMembers(merchantA.getMerchantId(), merchantAAdminUser);
        assertEquals(2, members.size());
    }

    @Test
    public void testMerchantAdminCanUpdateMemberRole() {
        AddMerchantMemberRequest req = new AddMerchantMemberRequest("member@alpha.com", "Pass123!", "Member", "REVIEWER");
        MerchantMemberDto member = merchantMemberService.addMerchantMember(merchantA.getMerchantId(), req, merchantAAdminUser);

        UpdateMemberRoleRequest updateReq = new UpdateMemberRoleRequest("MERCHANT_ADMIN");
        MerchantMemberDto updated = merchantMemberService.updateMemberRole(merchantA.getMerchantId(), member.getUserId(), updateReq, merchantAAdminUser);

        assertEquals("MERCHANT_ADMIN", updated.getRoleName());
    }

    @Test
    public void testMerchantAdminCanRemoveMember() {
        AddMerchantMemberRequest req = new AddMerchantMemberRequest("temp@alpha.com", "Pass123!", "Temp Member", "REVIEWER");
        MerchantMemberDto member = merchantMemberService.addMerchantMember(merchantA.getMerchantId(), req, merchantAAdminUser);

        merchantMemberService.removeMerchantMember(merchantA.getMerchantId(), member.getUserId(), merchantAAdminUser);

        List<MerchantMemberDto> members = merchantMemberService.getMerchantMembers(merchantA.getMerchantId(), merchantAAdminUser);
        assertEquals(1, members.size());
    }

    @Test
    public void testCrossTenantAccessIsBlocked() {
        // Merchant A Admin trying to view members of Merchant B
        assertThrows(TenantAccessDeniedException.class, () ->
                merchantMemberService.getMerchantMembers(merchantB.getMerchantId(), merchantAAdminUser));

        // Merchant A Admin trying to add member to Merchant B
        AddMerchantMemberRequest req = new AddMerchantMemberRequest("hacker@beta.com", "Pass123!", "Hacker", "REVIEWER");
        assertThrows(TenantAccessDeniedException.class, () ->
                merchantMemberService.addMerchantMember(merchantB.getMerchantId(), req, merchantAAdminUser));
    }

    @Test
    public void testReviewerRoleCannotManageMembers() {
        // Add a Reviewer to Merchant A
        AddMerchantMemberRequest req = new AddMerchantMemberRequest("reviewer@alpha.com", "Pass123!", "Reviewer", "REVIEWER");
        MerchantMemberDto reviewerDto = merchantMemberService.addMerchantMember(merchantA.getMerchantId(), req, merchantAAdminUser);

        AuthenticatedUser reviewerUser = userPrincipalResolver.resolveUser(reviewerDto.getUserId());

        // Reviewer can read members
        List<MerchantMemberDto> members = merchantMemberService.getMerchantMembers(merchantA.getMerchantId(), reviewerUser);
        assertEquals(2, members.size());

        // Reviewer CANNOT add new members
        AddMerchantMemberRequest addReq = new AddMerchantMemberRequest("new@alpha.com", "Pass123!", "New User", "REVIEWER");
        assertThrows(TenantAccessDeniedException.class, () ->
                merchantMemberService.addMerchantMember(merchantA.getMerchantId(), addReq, reviewerUser));
    }
}
