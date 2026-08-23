package com.tszkinyu.lending.decision.workflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record ApplicationSubmittedEvent(
        UUID eventId,
        UUID applicationId,
        String eventType,
        int schemaVersion,
        String applicantReference,
        int creditScore,
        BigDecimal annualIncome,
        BigDecimal requestedAmount,
        Instant submittedAt) {}
