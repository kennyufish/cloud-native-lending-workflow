[CmdletBinding()]
param(
    [int]$TimeoutSeconds = 120,
    [switch]$KeepServices
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PowerShell 7 or newer is required.'
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$projectName = 'cloud-native-lending-workflow'
$applicationUrl = 'http://localhost:8080'
$prometheusUrl = 'http://localhost:9090'
$grafanaUrl = 'http://localhost:3000'
$tempoUrl = 'http://localhost:3200'
$failed = $true

Set-Location $projectRoot

function Invoke-Compose {
    param([Parameter(Mandatory)][string[]]$Arguments)

    & docker compose -p $projectName @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Invoke-ComposeText {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & docker compose -p $projectName @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
    return ($output -join "`n")
}

function Wait-Http {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$Seconds = $TimeoutSeconds
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                return $response
            }
        } catch {
            # The service may still be starting; retry until the deadline.
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Uri"
}

function Get-DlqVisibleCount {
    $queueUrl = 'http://localhost:4566/000000000000/application-events-dlq'
    $json = Invoke-ComposeText @(
        'exec', '-T', 'localstack', 'awslocal', 'sqs', 'get-queue-attributes',
        '--queue-url', $queueUrl, '--attribute-names', 'ApproximateNumberOfMessages', '--output', 'json'
    )
    $attributes = $json | ConvertFrom-Json
    return [int]$attributes.Attributes.ApproximateNumberOfMessages
}

try {
    Write-Host 'Starting local lending workflow...' -ForegroundColor Cyan
    Invoke-Compose @('up', '--build', '--wait', '-d')

    Wait-Http "$applicationUrl/actuator/health/readiness" | Out-Null
    Wait-Http 'http://localhost:8081/actuator/health/readiness' | Out-Null
    Wait-Http "$prometheusUrl/-/ready" | Out-Null
    Wait-Http "$grafanaUrl/api/health" | Out-Null
    Wait-Http "$tempoUrl/ready" | Out-Null

    $traceId = ([Guid]::NewGuid()).ToString('N')
    $parentSpanId = ([Guid]::NewGuid()).ToString('N').Substring(0, 16)
    $traceparent = "00-$traceId-$parentSpanId-01"
    $idempotencyKey = "smoke-$([Guid]::NewGuid().ToString('N'))"
    $payload = @{
        applicantReference = 'SMOKE_001'
        creditScore = 760
        annualIncome = 120000
        requestedAmount = 15000
    } | ConvertTo-Json -Compress
    $headers = @{
        'Idempotency-Key' = $idempotencyKey
        traceparent = $traceparent
    }

    $firstResponse = Invoke-WebRequest `
        -Uri "$applicationUrl/api/v1/applications" `
        -Method Post `
        -Headers $headers `
        -ContentType 'application/json' `
        -Body $payload `
        -UseBasicParsing
    if ($firstResponse.StatusCode -ne 201) {
        throw "Expected 201 for the first submission, got $($firstResponse.StatusCode)"
    }
    $first = $firstResponse.Content | ConvertFrom-Json

    $replayResponse = Invoke-WebRequest `
        -Uri "$applicationUrl/api/v1/applications" `
        -Method Post `
        -Headers $headers `
        -ContentType 'application/json' `
        -Body $payload `
        -UseBasicParsing
    if ($replayResponse.StatusCode -ne 200) {
        throw "Expected 200 for the idempotent replay, got $($replayResponse.StatusCode)"
    }
    $replay = $replayResponse.Content | ConvertFrom-Json
    if ($first.id -ne $replay.id -or -not $replay.replayed) {
        throw 'Idempotency replay did not return the original application.'
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $view = Invoke-RestMethod -Uri "$applicationUrl/api/v1/applications/$($first.id)" -TimeoutSec 5
        if ($view.status -eq 'OFFERED') {
            break
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($view.status -ne 'OFFERED') {
        throw "Expected OFFERED after asynchronous review, got $($view.status)"
    }
    if ($view.notificationStatus -ne 'SIMULATED') {
        throw "Expected simulated notification, got $($view.notificationStatus)"
    }
    $auditTypes = @($view.audit | ForEach-Object { $_.type })
    foreach ($requiredType in @('APPLICATION_SUBMITTED', 'ASYNC_REVIEW_COMPLETED', 'OFFER_GENERATED', 'APPLICANT_NOTIFIED')) {
        if ($auditTypes -notcontains $requiredType) {
            throw "Missing audit record: $requiredType"
        }
    }
    foreach ($requiredType in @('APPLICATION_SUBMITTED', 'ASYNC_REVIEW_COMPLETED', 'OFFER_GENERATED', 'APPLICANT_NOTIFIED')) {
        $audit = @($view.audit | Where-Object { $_.type -eq $requiredType }) | Select-Object -First 1
        if ($audit.traceId -ne $traceId) {
            throw "Trace propagation check failed for ${requiredType}: expected $traceId, got $($audit.traceId)"
        }
    }

    # Tempo's trace endpoint is the independent evidence that the OTLP pipeline received the trace.
    Wait-Http "$tempoUrl/api/traces/$traceId" | Out-Null

    $initialDlqCount = Get-DlqVisibleCount
    $malformedBody = '{"eventType":'
    Invoke-Compose @(
        'exec', '-T', 'localstack', 'awslocal', 'sqs', 'send-message',
        '--queue-url', 'http://localhost:4566/000000000000/application-events',
        '--message-body', $malformedBody
    )

    $dlqDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $dlqCount = 0
    do {
        $dlqCount = Get-DlqVisibleCount
        if ($dlqCount -gt $initialDlqCount) {
            break
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $dlqDeadline)
    if ($dlqCount -le $initialDlqCount) {
        throw 'Malformed event did not reach the dead-letter queue.'
    }

    $applicationMetrics = (Invoke-WebRequest -Uri "$applicationUrl/actuator/prometheus" -UseBasicParsing).Content
    $workerMetrics = (Invoke-WebRequest -Uri 'http://localhost:8081/actuator/prometheus' -UseBasicParsing).Content
    if ($applicationMetrics -notmatch 'lending_outbox_published_total') {
        throw 'Application Prometheus endpoint did not expose outbox metrics.'
    }
    if ($workerMetrics -notmatch 'lending_decision_processed_total') {
        throw 'Worker Prometheus endpoint did not expose decision metrics.'
    }

    Write-Host "PASS: application $($first.id) reached OFFERED" -ForegroundColor Green
    Write-Host 'PASS: duplicate Idempotency-Key replayed the same application' -ForegroundColor Green
    Write-Host 'PASS: notification and append-only audit records were observed' -ForegroundColor Green
    Write-Host "PASS: trace $traceId propagated through SQS, callback, and Tempo" -ForegroundColor Green
    Write-Host "PASS: malformed event reached DLQ (visible count: $dlqCount)" -ForegroundColor Green
    Write-Host 'PASS: application and worker Prometheus endpoints expose custom metrics' -ForegroundColor Green
    Write-Host 'PASS: Prometheus, Grafana, and Tempo readiness endpoints responded' -ForegroundColor Green
    $failed = $false
} finally {
    if (-not $KeepServices -and -not $failed) {
        Write-Host 'Stopping local lending workflow and removing its named volumes...' -ForegroundColor DarkCyan
        Invoke-Compose @('down', '--volumes', '--remove-orphans')
    } elseif ($failed) {
        Write-Host 'Smoke test failed; services are left running for inspection. Run docker compose -p cloud-native-lending-workflow down --volumes to clean up.' -ForegroundColor Yellow
    }
}
