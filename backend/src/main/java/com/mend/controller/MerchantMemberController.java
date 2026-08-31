package com.mend.controller;

import com.mend.dto.AddMerchantMemberRequest;
import com.mend.dto.MerchantMemberDto;
import com.mend.dto.UpdateMemberRoleRequest;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.service.MerchantMemberService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/members")
public class MerchantMemberController {

    private final MerchantMemberService merchantMemberService;

    public MerchantMemberController(MerchantMemberService merchantMemberService) {
        this.merchantMemberService = merchantMemberService;
    }

    @GetMapping
    public ResponseEntity<List<MerchantMemberDto>> getMembers(
            @PathVariable UUID merchantId,
            @CurrentUser AuthenticatedUser currentUser) {
        List<MerchantMemberDto> members = merchantMemberService.getMerchantMembers(merchantId, currentUser);
        return ResponseEntity.ok(members);
    }

    @PostMapping
    public ResponseEntity<MerchantMemberDto> addMember(
            @PathVariable UUID merchantId,
            @RequestBody AddMerchantMemberRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        MerchantMemberDto member = merchantMemberService.addMerchantMember(merchantId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    @PutMapping("/{userId}/role")
    public ResponseEntity<MerchantMemberDto> updateMemberRole(
            @PathVariable UUID merchantId,
            @PathVariable UUID userId,
            @RequestBody UpdateMemberRoleRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        MerchantMemberDto updated = merchantMemberService.updateMemberRole(merchantId, userId, request, currentUser);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID merchantId,
            @PathVariable UUID userId,
            @CurrentUser AuthenticatedUser currentUser) {
        merchantMemberService.removeMerchantMember(merchantId, userId, currentUser);
        return ResponseEntity.noContent().build();
    }
}
