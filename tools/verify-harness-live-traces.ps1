param(
    [string]$ResultFile = 'target/harness-live/results.json',
    [string]$Output = 'target/harness-live/cloud-validation.json',
    [string]$CaseIds = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $report = Get-Content -Raw -LiteralPath $ResultFile | ConvertFrom-Json
    $base = [Environment]::GetEnvironmentVariable('LANGFUSE_BASE_URL')
    $publicKey = [Environment]::GetEnvironmentVariable('LANGFUSE_PUBLIC_KEY')
    $secretKey = [Environment]::GetEnvironmentVariable('LANGFUSE_SECRET_KEY')
    if ([string]::IsNullOrWhiteSpace($base) -or [string]::IsNullOrWhiteSpace($publicKey) -or
            [string]::IsNullOrWhiteSpace($secretKey)) {
        throw 'Langfuse cloud read credentials are not configured in process environment'
    }
    $authorization = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($publicKey + ':' + $secretKey))
    $startedValue = $report.startedUtc
    $startedUtc = if ($startedValue -is [DateTime]) {
        [DateTimeOffset]$startedValue
    } else {
        [DateTimeOffset]::Parse([string]$startedValue).ToUniversalTime()
    }
    $fromStart = [Uri]::EscapeDataString(($startedUtc.AddMinutes(-1)).ToString('o'))
    $toStart = [Uri]::EscapeDataString(([DateTimeOffset]::UtcNow.AddMinutes(1)).ToString('o'))
    $safeSentinels = @('ALPHA=17', 'BETA=25', 'ORBIT-7315', 'NOVA-9142', 'ZETA-4826')
    $secretValues = @($publicKey, $secretKey, [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY')) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $attempts = @($report.attempts)
    if (-not [string]::IsNullOrWhiteSpace($CaseIds)) {
        $ids = @($CaseIds.Split(',') | ForEach-Object { $_.Trim() })
        $attempts = @($attempts | Where-Object { $_.caseId -in $ids })
    }
    $summaries = foreach ($attempt in $attempts) {
        if ([string]::IsNullOrWhiteSpace($attempt.traceId)) {
            [ordered]@{ caseId=$attempt.caseId; queried=$false; reason='no trace id'; traceId=$null }
            continue
        }
        $url = $base.TrimEnd('/') + '/api/public/v2/observations?fromStartTime=' + $fromStart +
            '&toStartTime=' + $toStart + '&traceId=' + [Uri]::EscapeDataString($attempt.traceId) +
            '&fields=core,basic,metadata,model,usage,io&limit=100'
        try {
            $response = Invoke-RestMethod -Uri $url -Headers @{ Authorization = 'Basic ' + $authorization } -TimeoutSec 20
            $items = @($response.data)
            $ids = @($items | ForEach-Object { $_.id })
            $roots = @($items | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.parentObservationId) })
            $generations = @($items | Where-Object type -eq 'GENERATION')
            $tools = @($items | Where-Object type -eq 'TOOL')
            $danglingParents = @($items | Where-Object {
                -not [string]::IsNullOrWhiteSpace([string]$_.parentObservationId) -and
                $_.parentObservationId -notin $ids
            }).Count
            $agentIds = @($items | Where-Object type -eq 'AGENT' | ForEach-Object id)
            $orphanGenerationParents = @($generations | Where-Object { $_.parentObservationId -notin $agentIds }).Count
            $orphanToolParents = @($tools | Where-Object { $_.parentObservationId -notin $agentIds }).Count
            $taskIds = @($items | ForEach-Object {
                if ($_.metadata -and $_.metadata.task_id) { [string]$_.metadata.task_id }
            } | Where-Object { $_ }) | Select-Object -Unique
            $toolCallIds = @($generations | ForEach-Object {
                if ($_.metadata -and $_.metadata.tool_call_ids) { @($_.metadata.tool_call_ids) }
            } | ForEach-Object { $_ })
            $observedToolIds = @($tools | ForEach-Object {
                if ($_.metadata -and $_.metadata.tool_call_id) { [string]$_.metadata.tool_call_id }
            })
            $toolCallIdsMatch = $tools.Count -eq 0 -or
                (@($toolCallIds | Sort-Object -Unique) -join ',') -eq (@($observedToolIds | Sort-Object -Unique) -join ',')
            $orderedGenerations = @($generations | Sort-Object -Property startTime)
            $usageSamples = @($attempt.usageSamples)
            $usageMatches = 0
            $usageCompared = [Math]::Min($orderedGenerations.Count, $usageSamples.Count)
            for ($index = 0; $index -lt $usageCompared; $index++) {
                $details = $orderedGenerations[$index].usageDetails
                $sample = $usageSamples[$index]
                if ($null -ne $details -and [int]$details.input -eq [int]$sample.inputUncached -and
                        [int]$details.input_cached -eq [int]$sample.inputCached -and
                        [int]$details.output -eq [int]$sample.output) { $usageMatches++ }
            }
            $raw = ConvertTo-Json -InputObject $items -Depth 40 -Compress
            $credentialLeak = @($secretValues | Where-Object { $raw.Contains([string]$_) }).Count -gt 0
            $fixtureLeak = @($safeSentinels | Where-Object { $raw.Contains($_) }).Count -gt 0
            $generationUsage = @($orderedGenerations | ForEach-Object {
                if ($_.usageDetails) {
                    [ordered]@{ model=$_.model; input=$_.usageDetails.input; cachedInput=$_.usageDetails.input_cached;
                        output=$_.usageDetails.output; observedTotalInput=$_.inputUsage; observedOutput=$_.outputUsage }
                }
            })
            $nonEmptyIo = @($items | Where-Object {
                (-not [string]::IsNullOrWhiteSpace([string]$_.input)) -or
                (-not [string]::IsNullOrWhiteSpace([string]$_.output))
            }).Count -gt 0
            $timeOrderValid = $true
            foreach ($item in $items) {
                try {
                    $childStart = [DateTimeOffset]::Parse($item.startTime)
                    $childEnd = [DateTimeOffset]::Parse($item.endTime)
                    if ($childEnd -lt $childStart) { $timeOrderValid = $false; break }
                    if (-not [string]::IsNullOrWhiteSpace([string]$item.parentObservationId)) {
                        $parent = $items | Where-Object id -eq $item.parentObservationId | Select-Object -First 1
                        if ($null -eq $parent -or
                                $childStart -lt ([DateTimeOffset]::Parse($parent.startTime).AddSeconds(-1)) -or
                                $childEnd -gt ([DateTimeOffset]::Parse($parent.endTime).AddSeconds(1))) {
                            $timeOrderValid = $false; break
                        }
                    }
                } catch { $timeOrderValid = $false; break }
            }
            [ordered]@{
                caseId=$attempt.caseId; queried=$true; traceId=$attempt.traceId; observations=$items.Count
                roots=$roots.Count; rootName=$(if($roots.Count -gt 0){$roots[0].name}else{$null})
                generations=$generations.Count; tools=$tools.Count; agentNodes=$agentIds.Count
                danglingParents=$danglingParents; generationsOutsideAgent=$orphanGenerationParents
                toolsOutsideAgent=$orphanToolParents; taskIdMatches=($taskIds -contains [string]$attempt.taskId)
                toolCallIdsMatch=$toolCallIdsMatch; temporalOrderValid=$timeOrderValid
                usageMatches=$usageMatches; usageObservations=$usageCompared
                generationsUsage=$generationUsage
                hasNonEmptyObservationIo=$nonEmptyIo
                fixtureTextDetected=$fixtureLeak; credentialTextDetected=$credentialLeak
                errorObservations=@($items | Where-Object level -eq 'ERROR').Count
                toolStatuses=@($tools | ForEach-Object { [ordered]@{ name=$_.name; level=$_.level;
                    errorType=$(if($_.metadata -and $_.metadata.tool_error_type){[string]$_.metadata.tool_error_type}else{$null});
                    callIdPresent=(-not [string]::IsNullOrWhiteSpace([string]$_.metadata.tool_call_id));
                    statusMessagePresent=(-not [string]::IsNullOrWhiteSpace([string]$_.statusMessage)) } })
            }
        } catch {
            $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { $_.Exception.GetType().Name }
            [ordered]@{ caseId=$attempt.caseId; queried=$false; traceId=$attempt.traceId; reason=[string]$status }
        }
    }
    $outputPath = Join-Path $root $Output
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
    [ordered]@{ kind='langfuse-cloud-trace-structure-audit'; checkedUtc=[DateTimeOffset]::UtcNow.ToString('o'); cases=@($summaries) } |
        ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $outputPath -Encoding utf8
    foreach ($item in $summaries) {
        if ($item.queried) {
            'cloud ' + $item.caseId + ' spans=' + $item.observations + ' roots=' + $item.roots +
                ' generations=' + $item.generations + ' tools=' + $item.tools +
                ' dangling=' + $item.danglingParents + ' sensitiveText=' +
                ($item.fixtureTextDetected -or $item.credentialTextDetected)
        } else { 'cloud ' + $item.caseId + ' unavailable=' + $item.reason }
    }
} finally {
    Pop-Location
}
