$ErrorActionPreference='Stop'
$root='D:/pailcli/paicli-main'
$source=Join-Path $root 'target/harness-stage11'
$destination=Join-Path $root 'benchmarks/harness-live/results/stage11-2026-10-27'
function Read-Run($name) { Get-Content -Raw (Join-Path $source ($name+'.json')) | ConvertFrom-Json }
$task=(Get-Content -Raw (Join-Path $root 'benchmarks/harness-live/cases.json') | ConvertFrom-Json | Where-Object id -eq 'L05')
$rows=foreach ($name in @('live-before','live-after-1','live-after-2')) {
    $run=Read-Run $name; $a=$run.attempts[0]
    $legacy=$a.answer -eq $task.expected -and $a.tools.Count -ge $task.minToolCalls -and
        @($a.tools | Where-Object { -not $_.successful }).Count -ge $task.minFailedToolCalls -and
        'alpha.txt' -in @($a.tools | ForEach-Object path)
    $preserved=@($a.requests | Where-Object {
        $task.prompt -notin @($_.messages | Where-Object role -eq 'user' | ForEach-Object content)
    }).Count -eq 0
    $argsPreserved=$true
    foreach ($event in $a.tools) {
        $decision=$a.requests | Where-Object turn -eq $event.decisionTurn
        $call=$decision.response.toolCalls | Where-Object id -eq $event.callId
        if ($null -eq $call -or $call.function.name -ne $event.name -or $call.function.arguments -ne $event.argumentsJson) { $argsPreserved=$false }
    }
    $seen=@($a.tools | Where-Object { $_.name -notin @($a.requests[0].tools | ForEach-Object name) }).Count
    [ordered]@{batch=$name;status=$a.status;legacyWouldPass=$legacy;strictVerdict=$a.orderedRecoveryVerdict;
        modelCalls=$a.modelCalls;toolCalls=$a.toolCalls;nonAdvertisedToolAttempts=$seen;
        inputTokens=$a.inputTokens;outputTokens=$a.outputTokens;uncachedInputTokens=$a.uncachedInputTokens;cachedInputTokens=$a.cachedInputTokens;
        durationMillis=$a.durationMillis;estimatedCostUsd=$null;traceId=$a.traceId;
        initialMessageTokensEstimated=$a.requests[0].messageTokensEstimated;initialProviderInputTokens=$a.usageSamples[0].inputTotal;
        allOriginalTaskConstraintsStillPresent=$preserved;decodedResponseArgumentsMatchToolExecution=$argsPreserved;
        compressedSummaryMarkerPresent=(@($a.requests | ForEach-Object messages | Where-Object { $_.content -like '[[]已压缩的历史对话摘要[]]*' }).Count -gt 0);
        staticBroadCataloguePresent=$a.requests[0].messages[0].content.Contains('你可以使用以下工具：');
        actualToolNames=@($a.requests[0].tools | ForEach-Object name);tools=$a.tools}
}
$beforeSource=Read-Run 'source-before'
$sourceAfter=[ordered]@{compactorSha256=(Get-FileHash (Join-Path $root 'src/main/java/com/paicli/memory/ConversationHistoryCompactor.java')).Hash;
    agentSha256=(Get-FileHash (Join-Path $root 'src/main/java/com/paicli/agent/Agent.java')).Hash;
    assemblerSha256=(Get-FileHash (Join-Path $root 'src/main/java/com/paicli/prompt/PromptAssembler.java')).Hash;
    kernelSha256=(Get-FileHash (Join-Path $root 'src/main/java/com/paicli/agent/AgentExecutionKernel.java')).Hash;
    promptSha256=(Get-FileHash (Join-Path $root 'src/main/resources/prompts/base.md')).Hash;
    casesSha256=(Get-FileHash (Join-Path $root 'benchmarks/harness-live/cases.json')).Hash}
$sourceAfter | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $source 'source-after.json')
$cloud=foreach ($name in @('history-before-cloud','history-after-cloud','live-before-cloud','live-after-1-cloud','live-after-2-cloud')) {
    $trace=(Read-Run $name).cases[0]
    [ordered]@{batch=$name;traceId=$trace.traceId;queried=$trace.queried;generations=$trace.generations;tools=$trace.tools;
        usageMatches=$trace.usageMatches;usageObservations=$trace.usageObservations;danglingParents=$trace.danglingParents;
        taskIdMatches=$trace.taskIdMatches;toolCallIdsMatch=$trace.toolCallIdsMatch;temporalOrderValid=$trace.temporalOrderValid;
        hasUploadedIo=$trace.hasNonEmptyObservationIo;credentialDetected=$trace.credentialTextDetected}
}
$report=[ordered]@{kind='stage11-l05-order-investigation';generatedUtc=[DateTimeOffset]::UtcNow.ToString('o');
    judgeVersion='ordered-recovery-v2';originalPromptUnchanged=$true;
    compressionCauseExcluded=[ordered]@{compactorUnchanged=($beforeSource.compactorSha256 -eq $sourceAfter.compactorSha256);
        kernelUnchanged=($beforeSource.kernelSha256 -eq $sourceAfter.kernelSha256);
        evidence='One external user turn; full summary retains at least three and returns without requesting a summary; historical logs grow 2/4/6/8 messages. Current saved requests contain original user text, no summary marker.';
        historicalFullContextAvailable=$false};
    historicalRejudge=(Read-Run 'rejudged-stage10-final');currentRuns=$rows;
    rootHarnessDefect='System catalogue claimed glob_files/list_dir although actual tool definitions exposed only read_file. Captured baseline attempted both non-advertised tools during recovery.';
    productionChange='ReAct updates its real system message from the effective exposed tools and disables the advertised set on finalization; shared prompt distinguishes control dependencies and fault-injection arguments.';
    promptEquivalence=(Read-Run 'prompt-equivalence');sourceBefore=$beforeSource;sourceAfter=$sourceAfter;
    finalTests=[ordered]@{jdk='17.0.18';tests=33;failures=0;errors=0;skipped=0;initialEnvironmentError='JDK 23 Byte Buddy instrumentation unsupported; rerun using project JDK17, no dependency changes'};
    offline=(Read-Run 'offline-final');cloud=$cloud;
    conclusion='Tool advertisement mismatch fixed and non-advertised attempts observed 2 -> 0/0. L05 is not reliably solved: strict result 1/1 before, 1/2 after. Second post-run used path="{}" rather than an empty argument object, and was correctly failed.';
    limits=@('Three real executions total, same alias/settings but temperature=1 and no seed; no statistical success-rate or stable performance claim.',
        'Post-run 2 is a failed task; its lower token/time counts cannot be called equivalent-success efficiency.',
        'Historical first failed-call arguments and request bodies were not saved; exact {} cannot be proved, so old-before is insufficient evidence.',
        'No full 12-case live rerun because compaction was excluded; no live Plan/Team regression claim.',
        'Final context-rendering refactor avoids repeated external-context loads; all six recorded post-request system messages were independently shown byte-equivalent.',
        'No task Deadline, checkpoint system, idempotency or automatic reflection added. No price verified for execution date; costs unknown.')}
$report | ConvertTo-Json -Depth 35 | Set-Content -Encoding utf8 (Join-Path $destination 'comparison.json')
foreach ($name in @('live-before','live-after-1','live-after-2','history-before-cloud','history-after-cloud','live-before-cloud','live-after-1-cloud','live-after-2-cloud','rejudged-stage10-final','rejudged-current-final-1','rejudged-current-final-2','prompt-equivalence','source-before','source-after','offline-final')) {
    Copy-Item -LiteralPath (Join-Path $source ($name+'.json')) -Destination (Join-Path $destination ($name+'.json'))
}
$rows | ForEach-Object { [pscustomobject]$_ } | Select-Object batch,status,modelCalls,toolCalls,nonAdvertisedToolAttempts,inputTokens,outputTokens,durationMillis | ConvertTo-Json -Compress
