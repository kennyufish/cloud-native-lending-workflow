package com.tszkinyu.lending.decision.workflow;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

@Component
class DecisionPolicy {

    private static final BigDecimal MAX_AMOUNT_TO_INCOME = new BigDecimal("0.35");

    Decision evaluate(ApplicationSubmittedEvent application) {
        boolean approved = application.creditScore() >= 680
                && application.requestedAmount().compareTo(
                        application.annualIncome().multiply(MAX_AMOUNT_TO_INCOME)) <= 0;
        if (!approved) {
            return new Decision("DECLINED", null, null, "OUTSIDE_DEMO_POLICY");
        }
        BigDecimal annualPercentageRate = application.creditScore() >= 760
                ? new BigDecimal("6.49")
                : application.creditScore() >= 720
                        ? new BigDecimal("8.99")
                        : new BigDecimal("12.99");
        return new Decision("APPROVED", annualPercentageRate, 36, null);
    }

    record Decision(
            String decision,
            BigDecimal annualPercentageRate,
            Integer termMonths,
            String reason) {}
}
