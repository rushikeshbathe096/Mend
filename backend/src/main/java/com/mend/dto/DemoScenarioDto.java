package com.mend.dto;

import java.util.List;

/**
 * Catalog entry describing a deterministic demo scenario. This is static
 * product documentation (scenario id/title/description), never fabricated
 * business metrics. Every description starts with "SIMULATED": the scenarios run on fabricated inputs.
 */
public class DemoScenarioDto {

    private String id;
    private String title;
    private String description;
    private List<String> flow;

    public DemoScenarioDto() {
    }

    public DemoScenarioDto(String id, String title, String description, List<String> flow) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.flow = flow;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getFlow() { return flow; }
    public void setFlow(List<String> flow) { this.flow = flow; }

    public static List<DemoScenarioDto> catalog() {
        return List.of(
                new DemoScenarioDto("LOW_RISK_RETRY", "Low-Risk Automated Retry",
                        "SIMULATED: an insufficient_funds failure is classified by the deterministic Razorpay taxonomy, passes compliance, and is retried through the provider boundary (mock provider) to a recovered outcome.",
                        List.of("Failed payment", "Taxonomy class", "Strategy", "Compliance", "ActionIntent", "Provider execution", "Recovered")),
                new DemoScenarioDto("HIGH_RISK_HUMAN_REVIEW", "High-Risk Human Review",
                        "SIMULATED: a high-value failure is held for merchant review by the demo (a review item, not an AI decision); approving it revalidates compliance before execution.",
                        List.of("Failed payment", "Taxonomy class", "Review item", "Merchant approval", "Compliance revalidation", "Execution", "Outcome")),
                new DemoScenarioDto("CUSTOMER_ACTION", "Customer Action",
                        "SIMULATED: A card failure routes to a customer payment link; when the customer completes payment, the captured webhook reconciles the campaign to recovered.",
                        List.of("Card/payment failure", "CUSTOMER_ACTION_REQUIRED", "Payment link", "Customer action", "Captured webhook", "Reconciliation", "Recovered")),
                new DemoScenarioDto("PROVIDER_AMBIGUITY", "Provider Ambiguity Resolution",
                        "SIMULATED: A provider timeout leaves execution state uncertain; the authoritative provider event reconciles the campaign to its true final state.",
                        List.of("Provider timeout", "Execution uncertain", "Reconciliation", "Authoritative provider status", "Final state")),
                new DemoScenarioDto("DUPLICATE_EVENT", "Duplicate Event Interception",
                        "SIMULATED: The same webhook delivered twice is intercepted by strict deduplication: one business event, one recovery path.",
                        List.of("Webhook delivery", "Duplicate delivery", "Deduplication", "One business event")),
                new DemoScenarioDto("UNCLASSIFIED_FAILURE", "Unclassified Failure",
                        "SIMULATED: a failure whose error_reason has no documented mapping is UNCLASSIFIED; it is never guessed and no payment action is taken.",
                        List.of("Failed payment", "UNCLASSIFIED", "Ineligible", "No payment action", "Campaign closed"))
        );
    }
}
