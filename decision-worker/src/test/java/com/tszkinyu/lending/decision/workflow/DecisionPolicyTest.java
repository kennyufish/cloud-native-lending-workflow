package com.tszkinyu.lending.decision.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DecisionPolicyTest {

    private final DecisionPolicy policy = new DecisionPolicy();

    @Test
    void createsOfferWhenApplicationMeetsDemoPolicy() {
        DecisionPolicy.Decision decision = policy.evaluate(application(760, "120000", "25000"));

        assertThat(decision.decision()).isEqualTo("APPROVED");
        assertThat(decision.annualPercentageRate()).isEqualByComparingTo("6.49");
        assertThat(decision.termMonths()).isEqualTo(36);
        assertThat(decision.reason()).isNull();
    }

    @Test
    void declinesWhenCreditScoreIsBelowDemoThreshold() {
        DecisionPolicy.Decision decision = policy.evaluate(application(679, "120000", "25000"));

        assertThat(decision.decision()).isEqualTo("DECLINED");
        assertThat(decision.annualPercentageRate()).isNull();
        assertThat(decision.termMonths()).isNull();
        assertThat(decision.reason()).isEqualTo("OUTSIDE_DEMO_POLICY");
    }

    @Test
    void declinesWhenRequestedAmountExceedsIncomeRatio() {
        assertThat(policy.evaluate(application(800, "100000", "35000")).decision())
                .isEqualTo("APPROVED");
        assertThat(policy.evaluate(application(800, "100000", "35000.01")).decision())
                .isEqualTo("DECLINED");
    }

    private static ApplicationSubmittedEvent application(int score, String income, String amount) {
        return new ApplicationSubmittedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "APPLICATION_SUBMITTED",
                1,
                "APPLICANT-TEST",
                score,
                new BigDecimal(income),
                new BigDecimal(amount),
                Instant.parse("2026-08-23T12:00:00Z"));
    }
}
