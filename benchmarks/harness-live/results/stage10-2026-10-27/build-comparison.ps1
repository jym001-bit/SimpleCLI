$ErrorActionPreference = 'Stop'
$root = 'D:/pailcli/paicli-main'
$inputDir = Join-Path $root 'target/harness-stage10'
$outputDir = Join-Path $root 'benchmarks/harness-live/results/stage10-2026-10-27'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

function Read-Run($name) { Get-Content -Raw -LiteralPath (Join-Path $inputDir ($name + '.json')) | ConvertFrom-Json }
function Summarize($name) {
    $run = Read-Run $name
    $m = [ordered]@{ total=$run.total; passed=$run.passed; successRate=($run.passed / $run.total); datasetSha256=$run.datasetSha256; model=$run.model }
    foreach ($field in @('modelCalls','toolCalls','inputTokens','uncachedInputTokens','cachedInputTokens','outputTokens','durationMillis')) {
        $m[$field] = ($run.attempts | Measure-Object -Property $field -Sum).Sum
    }
    $m['estimatedCostUsd'] = $null
    $m['costReason'] = 'No verified rate for 2026-10-27; the existing snapshot is date-scoped to 2026-10-26.'
    $m['failedCases'] = @($run.attempts | Where-Object status -ne 'passed' | Select-Object caseId,judgement,answer,traceId)
    return $m
}
function Check-Cloud($name, $resultName) {
    $run = Read-Run $name
    $expected = Read-Run $resultName
    $invalid = @(foreach ($trace in $run.cases) {
        $attempt = $expected.attempts | Where-Object caseId -eq $trace.caseId
        if (-not $trace.queried -or $trace.roots -ne 1 -or $trace.danglingParents -ne 0 -or
            -not $trace.taskIdMatches -or -not $trace.toolCallIdsMatch -or -not $trace.temporalOrderValid -or
            $trace.generationsOutsideAgent -ne 0 -or $trace.toolsOutsideAgent -ne 0 -or
            $trace.generations -ne $attempt.modelCalls -or $trace.tools -ne $attempt.toolCalls -or
            $trace.usageMatches -ne $trace.usageObservations -or $trace.hasNonEmptyObservationIo -or
            $trace.fixtureTextDetected -or $trace.credentialTextDetected) { $trace }
    })
    return [ordered]@{ traces=$run.cases.Count; valid=($run.cases.Count - $invalid.Count); invalid=@($invalid | Select-Object caseId,traceId) }
}

$before = Read-Run 'live-before'; $after = Read-Run 'live-after'
$orderAudit = foreach ($entry in @(@('before',$before),@('after',$after))) {
    $case = $entry[1].attempts | Where-Object caseId -eq 'L05'
    $valid = $case.tools.Count -ge 2 -and -not $case.tools[0].successful -and $case.tools[1].successful -and $case.tools[1].path -eq 'alpha.txt'
    [ordered]@{ batch=$entry[0]; caseId='L05'; firstToolFailedThenAlphaRead=$valid; automaticStatus=$case.status; tools=$case.tools }
}
$beforeDiag = Read-Run 'diagnostic-before'; $afterDiag = Read-Run 'diagnostic-after'
$diagnosticPairs = foreach ($a in $beforeDiag.attempts) {
    $b = $afterDiag.attempts | Where-Object caseId -eq $a.caseId
    [ordered]@{ caseId=$a.caseId; before=$a; after=$b }
}
$repeat = Read-Run 'l08-after-repeat'
$report = [ordered]@{
    kind='stage10-real-model-paired-task-comparison'
    generatedUtc=[DateTimeOffset]::UtcNow.ToString('o')
    controlledChange='Single-chunk conversation summary receives an input-relative soft length target; no truncation or acceptance guard changes.'
    configuration=[ordered]@{model='deepseek-v4-flash';temperature=1.0;topP=0.95;thinking='enabled';reasoningEffort='max';seed=$null;safeTools=@('read_file');executionMode='sequential';sameTaskPrompts=$true;internalSummaryPromptChanged=$true;vendorBackendRevisionPinned=$false}
    fixed12=[ordered]@{before=(Summarize 'live-before');after=(Summarize 'live-after');automaticScoreLimitation='L05 checks occurrence but not order. Supplemental order audit is applied to both batches and is reported separately.';l05OrderAudit=$orderAudit;beforeScoreWithOnlyThisAdditionalAudit=12;afterScoreWithOnlyThisAdditionalAudit=11}
    diagnostic3=[ordered]@{before=(Summarize 'diagnostic-before');after=(Summarize 'diagnostic-after');pairs=$diagnosticPairs;limitation='D01 fails exact JSON value equality in both batches while all required summary fact markers remain present; task field-value conventions are ambiguous. Criteria were not changed.'}
    l08=[ordered]@{
        before=@($before.attempts | Where-Object caseId -eq 'L08')[0]
        beforeCompactionLog=[ordered]@{beforeTokensEstimated=3472;afterTokensEstimated=3338;compressionRatioEstimated=(1-3338.0/3472);summaryChars=815;source='application log 2026-10-27 13:46:07.917'}
        after=@($after.attempts | Where-Object caseId -eq 'L08')[0]
        afterSummaryChars=626
        afterRepeat=$repeat.attempts[0]
        afterRepeatSummaryChars=532
        repeatPolicy='One baseline and two post-change L08 executions; every attempt retained, none selected as best.'
    }
    offline35=[ordered]@{before=(Read-Run 'offline-before');after=(Read-Run 'offline-after')}
    targetedTests=[ordered]@{classes=@('ConversationHistoryCompactorTest','AutoCompactionManagerTest');tests=19;failures=0;errors=0;skipped=0}
    cloud=[ordered]@{fixedBefore=(Check-Cloud 'cloud-before' 'live-before');fixedAfter=(Check-Cloud 'cloud-after' 'live-after');diagnosticBefore=(Check-Cloud 'diagnostic-cloud-before' 'diagnostic-before');diagnosticAfter=(Check-Cloud 'diagnostic-cloud-after' 'diagnostic-after');repeat=(Check-Cloud 'l08-repeat-cloud' 'l08-after-repeat')}
    telemetryIssue=[ordered]@{caseId='L06';traceId='83ba06c7be4668baec49bc33c44d7fe0';expectedGenerations=3;observedGenerations=2;expectedTools=2;observedTools=1;exportFailure='Failed to export 3 spans: HttpTimeoutException';recheckStillIncomplete=$true;productionTelemetryModified=$false}
    conclusion='Shorter retained context observed; no task success gain and no proven overall token/cost/latency improvement. Full-set input/output tokens increased. L05 order compliance worsened without entering the changed compactor path.'
    limitations=@('Small paired samples; temperature=1 and no seed; independently generated priming replies are not byte-identical.',
        'L05/L07 never invoke the changed summary code in this dataset; differences are observed but not attributed to the optimization.',
        'Cached input and network/model latency vary; lower summed duration is not causal evidence of speedup.',
        'Required fact checks are finite deterministic checks, not proof that all semantics and hallucinations are covered.',
        'Existing full-suite failures were not rerun or repaired; no Docker or large Benchmark.',
        'One optional detailed cloud request failed with a transport reset; its unverified artifact was excluded. Baseline L06 primary trace remains incomplete after an OTLP export timeout.')
}
$report | ConvertTo-Json -Depth 35 | Set-Content -Encoding utf8 (Join-Path $outputDir 'comparison.json')
foreach ($name in @('live-before','live-after','diagnostic-before','diagnostic-after','l08-after-repeat','offline-before','offline-after','cloud-before','cloud-after','diagnostic-cloud-before','diagnostic-cloud-after','l08-repeat-cloud','l06-cloud-recheck','source-before','source-after','l08-cloud-before-observations','l08-cloud-after-observations')) {
    Copy-Item -LiteralPath (Join-Path $inputDir ($name+'.json')) -Destination (Join-Path $outputDir ($name+'.json'))
}
Write-Output "Comparison saved: $outputDir/comparison.json"
$report.cloud | ConvertTo-Json -Depth 5 -Compress
