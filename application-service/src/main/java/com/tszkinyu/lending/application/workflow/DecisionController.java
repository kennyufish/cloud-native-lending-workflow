package com.tszkinyu.lending.application.workflow;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import com.tszkinyu.lending.application.config.LendingProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/decisions")
class DecisionController {

    private final ApplicationWorkflow workflow;
    private final byte[] expectedToken;

    DecisionController(ApplicationWorkflow workflow, LendingProperties properties) {
        this.workflow = workflow;
        this.expectedToken = properties.internalApiToken().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    ApplicationWorkflow.ApplicationView record(
            @RequestHeader("X-Internal-Token") String token,
            @Valid @RequestBody DecisionRequest request) {
        if (!MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal token");
        }
        return workflow.recordDecision(request);
    }

    record DecisionRequest(
            @NotNull UUID eventId,
            @NotNull UUID applicationId,
            @NotNull @Pattern(regexp = "APPROVED|DECLINED") String decision,
            @DecimalMin("0.01") @DecimalMax("99.99") BigDecimal annualPercentageRate,
            @DecimalMin("1") @DecimalMax("120") Integer termMonths,
            @Size(max = 120) String reason) {}
}
