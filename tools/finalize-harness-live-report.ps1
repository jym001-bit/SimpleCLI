param(
    [string]$Output = 'benchmarks/harness-live/results/real-model-evaluation-2026-10-26.json'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $read = { param($path) Get-Content -Raw -LiteralPath $path | ConvertFrom-Json }
    $probe = & $read 'target/harness-live/probe.json'
    $probeCloud = & $read 'target/harness-live/probe-cloud-validation.json'
    $networkRun = & $read 'target/harness-live/baseline.json'
    $fixedRun = & $read 'target/harness-live/after-fixes.json'
    $compactionRun = & $read 'target/harness-live/compaction.json'
    $fixedCloud = & $read 'target/harness-live/after-fixes-cloud.json'
    $parallelRun = & $read 'target/harness-live/parallel.json'
    $parallelCloud = & $read 'target/harness-live/parallel-cloud.json'
    $errorTypeRun = & $read 'target/harness-live/error-type-verification.json'
    $errorTypeCloud = & $read 'target/harness-live/error-type-cloud.json'
    $pricing = & $read 'benchmarks/harness-live/pricing-snapshot-2026-10-26.json'
    $datasetPath = 'benchmarks/harness-live/cases.json'
    $datasetHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $datasetPath).Hash.ToLowerInvariant()

    $latest = @($fixedRun.attempts | Where-Object { $_.caseId -notin @('L08', 'L11') }) +
        @($compactionRun.attempts) + @($errorTypeRun.attempts)
    foreach ($attempt in $latest) {
        $usageSamples = @($attempt.usageSamples)
        $cost = $null
        $started = if ($attempt.startedUtc -is [DateTime]) {
            [DateTimeOffset]$attempt.startedUtc
        } else {
            [DateTimeOffset]::Parse([string]$attempt.startedUtc).ToUniversalTime()
        }
        $peak = (($started.Hour -ge 1 -and $started.Hour -lt 4) -or
                 ($started.Hour -ge 6 -and $started.Hour -lt 10))
        $supportedAlias = @('deepseek-flash', 'deepseek-v4-flash', 'deepseek-v4-flash-vision-exp') -contains
            ([string]$attempt.model).ToLowerInvariant()
        if ($started.ToString('yyyy-MM-dd') -eq $pricing.snapshotDate -and $peak -and
                $attempt.provider -eq 'deepseek' -and $supportedAlias -and
                $usageSamples.Count -eq [int]$attempt.modelCalls -and $attempt.modelFailedCalls -eq 0) {
            $amount = 0.0
            foreach ($usage in $usageSamples) {
                $amount += (([double]$usage.inputUncached * [double]$pricing.rates.inputCacheMiss + [double]$usage.inputCached * [double]$pricing.rates.inputCacheHit + [double]$usage.output * [double]$pricing.rates.output) / 1000000)
            }
            $cost = [Math]::Round($amount, 9)
        }
        $attempt | Add-Member -NotePropertyName estimatedCostUsd -NotePropertyValue $cost -Force
        $attempt | Add-Member -NotePropertyName pricingSnapshot -NotePropertyValue $(if ($null -eq $cost) { 'unavailable' } else { $pricing.snapshotDate + ':' + $pricing.tier }) -Force
    }

    $total = $latest.Count
    $passed = @($latest | Where-Object status -eq 'passed').Count
    $allUsage = @($latest | ForEach-Object { $_.usageSamples })
    $inputTotal = [long](($latest | Measure-Object inputTokens -Sum).Sum)
    $inputUncached = [long](($latest | Measure-Object uncachedInputTokens -Sum).Sum)
    $inputCached = [long](($latest | Measure-Object cachedInputTokens -Sum).Sum)
    $outputTokens = [long](($latest | Measure-Object outputTokens -Sum).Sum)
    $calls = [long](($latest | Measure-Object modelCalls -Sum).Sum)
    $toolCalls = [long](($latest | Measure-Object toolCalls -Sum).Sum)
    $duration = [long](($latest | Measure-Object durationMillis -Sum).Sum)
    $totalCost = [Math]::Round((($latest | Measure-Object estimatedCostUsd -Sum).Sum), 9)

    $outcome = [ordered]@{
        kind = 'real-model-harness-evaluation'
        generatedUtc = [DateTimeOffset]::UtcNow.ToString('o')
        dataset = [ordered]@{ path=$datasetPath; cases=12; sha256=$datasetHash }
        provider = 'deepseek'
        configuredModel = 'deepseek-v4-flash'
        effectiveModel = 'DeepSeek-V4.1-Flash-1017 (official legacy alias routing)'
        harnessVersion = '37b09ee+working-tree'
        currentDatasetLatestResults = [ordered]@{
            denominator = $total; passed = $passed; successRate = [Math]::Round($passed / $total, 4)
            modelCalls = $calls; toolCalls = $toolCalls; failedToolCalls = [long](($latest | ForEach-Object { @($_.tools | Where-Object { -not $_.successful }).Count } | Measure-Object -Sum).Sum)
            durationMillisSum = $duration; durationMillisMean = [Math]::Round($duration / $total, 1)
            inputTokensProviderTotal = $inputTotal; inputTokensCacheMiss = $inputUncached
            inputTokensCacheHit = $inputCached; outputTokens = $outputTokens
            estimatedCostUsd = $totalCost
            pricingSnapshot = $pricing
            attempts = $latest
        }
        allObservedRuns = [ordered]@{
            preFixProbe = $probe
            preFixProbeCloud = $probeCloud
            preFixNetworkInterrupted = $networkRun
            firstFixedRunDatasetV1 = $fixedRun
            compactionRetestDatasetV2 = $compactionRun
            fixedRunCloudAudit = $fixedCloud
            errorTypeVerification = $errorTypeRun
            errorTypeCloudAudit = $errorTypeCloud
            parallelSmokeRun = $parallelRun
            parallelCloudAudit = $parallelCloud
        }
        limitations = @(
            'The first full pre-fix run encountered provider DNS failures after two cases; those infrastructure errors are retained and excluded from task quality scoring.',
            'The latest 12-case result uses the latest attempt per case. L08 has two attempts: the initial context was too short for net compression; the expanded context triggered compaction and retained the answer.',
            'Model-call count is logical LlmClient chat invocations; provider-internal HTTP retries are not separately counted.',
            'No LLM judge was used. Final answers were checked by exact text or strict JSON plus actual tool results, paths, and call counts.',
            'The live tool registry exposes only read_file. Tool argument recovery, missing-file recovery, iteration limit, memory retrieval, and compaction use controlled local fixtures.',
            'Cloud scans covered configured credentials and synthetic fixture sentinels; no non-empty observation input/output was returned by the v2 read API.'
        )
    }
    $outputPath = Join-Path $root $Output
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
    $outcome | ConvertTo-Json -Depth 70 | Set-Content -LiteralPath $outputPath -Encoding utf8
    'wrote ' + $Output + ' latest=' + $passed + '/' + $total + ' attempts=' + $latest.Count +
        ' calls=' + $calls + ' tools=' + $toolCalls + ' estimatedUsd=' + $totalCost
} finally {
    Pop-Location
}
