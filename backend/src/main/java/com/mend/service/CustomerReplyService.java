package com.mend.service;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.CustomerReply;
import com.mend.domain.entity.ReplySuggestion;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.CustomerReplyRepository;
import com.mend.domain.repository.ReplySuggestionRepository;
import com.mend.dto.CustomerReplyDto;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.TenantGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Customer replies (M3 U2.4). Replies are PII: tenant-scoped (TenantGuard plus the campaign must belong to the
 * merchant), capped at 2000 characters, never logged or written to the audit trail; retention 90 days (purge M5-owned).
 */
@Service
public class CustomerReplyService {

    private final CampaignRepository campaignRepository;
    private final CustomerReplyRepository replyRepository;
    private final ReplySuggestionRepository suggestionRepository;
    private final AuditService auditService;

    public CustomerReplyService(CampaignRepository campaignRepository, CustomerReplyRepository replyRepository,
                                ReplySuggestionRepository suggestionRepository, AuditService auditService) {
        this.campaignRepository = campaignRepository;
        this.replyRepository = replyRepository;
        this.suggestionRepository = suggestionRepository;
        this.auditService = auditService;
    }

    @Transactional
    public CustomerReplyDto submit(UUID merchantId, UUID campaignId, String text, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);
        Campaign campaign = ownCampaign(merchantId, campaignId);
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.length() > CustomerReply.MAX_TEXT_LENGTH) {
            throw new InvalidRequestException("Reply text must be 1-" + CustomerReply.MAX_TEXT_LENGTH + " characters");
        }
        CustomerReply reply = replyRepository.saveAndFlush(
                new CustomerReply(UUID.randomUUID(), merchantId, campaign.getId(), trimmed, currentUser.getUserId()));
        auditService.logEvent(merchantId, campaign.getId(), "CUSTOMER_REPLY_RECORDED", "USER", currentUser.getUserId(),
                "reply=" + reply.getId());
        return CustomerReplyDto.from(reply, null);
    }

    @Transactional(readOnly = true)
    public List<CustomerReplyDto> list(UUID merchantId, UUID campaignId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);
        ownCampaign(merchantId, campaignId);
        List<CustomerReply> replies = replyRepository.findByCampaignIdAndMerchantIdOrderByCreatedAtDesc(campaignId, merchantId);
        Map<UUID, ReplySuggestion> byReply = suggestionRepository.findByReplyIdIn(replies.stream().map(CustomerReply::getId).toList())
                .stream().collect(Collectors.toMap(ReplySuggestion::getReplyId, Function.identity()));
        return replies.stream().map(r -> CustomerReplyDto.from(r, byReply.get(r.getId()))).toList();
    }

    /** A human retry: only a FAILED reply (it has no suggestion), so a reply never gets a second suggestion. */
    @Transactional
    public CustomerReplyDto retry(UUID merchantId, UUID campaignId, UUID replyId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);
        ownCampaign(merchantId, campaignId);
        CustomerReply reply = replyRepository.findByIdAndMerchantId(replyId, merchantId)
                .filter(r -> r.getCampaignId().equals(campaignId))
                .orElseThrow(() -> new ResourceNotFoundException("Reply not found: " + replyId));
        if (replyRepository.retryFailed(reply.getId(), merchantId, Instant.now()) != 1) {
            throw new InvalidRequestException("Only a FAILED reply can be retried (reply is " + reply.getStatus() + ")");
        }
        auditService.logEvent(merchantId, campaignId, "CUSTOMER_REPLY_RETRY", "USER", currentUser.getUserId(), "reply=" + replyId);
        return CustomerReplyDto.from(replyRepository.findById(replyId).orElseThrow(), null);
    }

    /** Clears a contact opt-out. Merchant admins only; audited. Returns whether an opt-out was cleared. */
    @Transactional
    public boolean clearOptOut(UUID merchantId, UUID campaignId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);
        if (!currentUser.hasMerchantRole(merchantId, "MERCHANT_ADMIN")) {
            throw new TenantAccessDeniedException("Clearing a contact opt-out requires MERCHANT_ADMIN");
        }
        ownCampaign(merchantId, campaignId);
        boolean cleared = campaignRepository.clearContactOptOut(campaignId, merchantId) == 1;
        if (cleared) {
            auditService.logEvent(merchantId, campaignId, "CONTACT_OPT_OUT_CLEARED", "USER", currentUser.getUserId(),
                    "code=CUSTOMER_OPTED_OUT cleared by merchant admin");
        }
        return cleared;
    }

    private Campaign ownCampaign(UUID merchantId, UUID campaignId) {
        return campaignRepository.findById(campaignId)
                .filter(c -> merchantId.equals(c.getMerchantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found: " + campaignId));
    }
}
