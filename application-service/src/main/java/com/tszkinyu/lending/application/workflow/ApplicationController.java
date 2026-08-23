package com.tszkinyu.lending.application.workflow;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/applications")
class ApplicationController {

    private final ApplicationWorkflow workflow;

    ApplicationController(ApplicationWorkflow workflow) {
        this.workflow = workflow;
    }

    @PostMapping
    ResponseEntity<SubmissionResponse> submit(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody SubmissionRequest request) {
        ApplicationWorkflow.Submission result = workflow.submit(idempotencyKey, request);
        SubmissionResponse response = new SubmissionResponse(
                result.id(), result.status(), result.replayed(), result.createdAt());
        if (result.replayed()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/api/v1/applications/" + result.id())).body(response);
    }

    @GetMapping("/{applicationId}")
    ApplicationWorkflow.ApplicationView get(@PathVariable UUID applicationId) {
        return workflow.get(applicationId);
    }

    record SubmissionRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z0-9_-]+") String applicantReference,
            @Min(300) @Max(850) int creditScore,
            @NotNull @DecimalMin("1000.00") @DecimalMax("10000000.00") BigDecimal annualIncome,
            @NotNull @DecimalMin("500.00") @DecimalMax("1000000.00") BigDecimal requestedAmount) {}

    record SubmissionResponse(UUID id, String status, boolean replayed, Instant createdAt) {}
}
