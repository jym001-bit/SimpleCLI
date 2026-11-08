param(
    [string]$Output = 'target/harness-offline-result.json',
    [string]$Baseline = ''
)

$ErrorActionPreference = 'Stop'
if (-not $env:MAVEN_OPTS) {
    $env:MAVEN_OPTS = '-Xmx256m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2 -XX:ReservedCodeCacheSize=64m'
}
if (-not $env:JAVA_TOOL_OPTIONS) {
    $env:JAVA_TOOL_OPTIONS = '-Xmx192m -XX:ActiveProcessorCount=2 -XX:ReservedCodeCacheSize=64m'
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$cases = Get-Content -Raw -LiteralPath (Join-Path $root 'benchmarks/harness-offline/cases.json') | ConvertFrom-Json
$selectors = ($cases | ForEach-Object { ([string]$_.class) + '#' + ([string]$_.method) }) -join ','
Push-Location $root
try {
    $runStarted = Get-Date
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'target') | Out-Null
    $mavenLog = Join-Path $root 'target/harness-offline-maven.log'
    & mvn test "-Dtest=$selectors" '-DskipTests=false' '-q' *> $mavenLog
    $mavenExit = $LASTEXITCODE
    if ($mavenExit -ne 0) {
        Get-Content -LiteralPath $mavenLog -Tail 18 | Write-Output
    }
    $reports = @{}
    foreach ($report in (Get-ChildItem -LiteralPath (Join-Path $root 'target/surefire-reports') -Filter 'TEST-*.xml')) {
        if ($report.LastWriteTime -lt $runStarted.AddSeconds(-2)) { continue }
        [xml]$xml = Get-Content -Raw -LiteralPath $report.FullName
        foreach ($item in $xml.testsuite.testcase) {
            $methodName = ([string]$item.name) -replace '\(.*$', ''
            $key = ([string]$item.classname) + '#' + $methodName
            $reports[$key] = $item
        }
    }
    $results = foreach ($case in $cases) {
        $key = ([string]$case.class) + '#' + ([string]$case.method)
        $item = $reports[$key]
        $passed = $null -ne $item -and $null -eq $item.failure -and $null -eq $item.error -and $null -eq $item.skipped
        $detail = if ($null -eq $item) { 'missing test result' }
                  elseif ($null -ne $item.failure) { [string]$item.failure.message }
                  elseif ($null -ne $item.error) { [string]$item.error.message }
                  elseif ($null -ne $item.skipped) { 'skipped' }
                  else { '' }
        [ordered]@{
            id = $case.id; goal = $case.goal; expected = $case.expect
            passed = $passed; failureCategory = $(if ($passed) { $null } else { $case.category })
            elapsedSeconds = $(if ($null -eq $item) { $null } else { [double]$item.time })
            error = $detail; test = $key
        }
    }
    $passedCount = @($results | Where-Object passed).Count
    $version = (& git rev-parse --short HEAD).Trim()
    $dirty = -not [string]::IsNullOrWhiteSpace(((& git status --porcelain) -join "`n"))
    $sourceFiles = @('src/main/java/com/paicli/agent/Agent.java',
                     'src/main/java/com/paicli/agent/AgentBudget.java',
                     'src/main/java/com/paicli/observability/LangfuseTelemetry.java',
                     'src/main/java/com/paicli/tool/ToolRegistry.java')
    $sourceHashes = ($sourceFiles | ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root $_)).Hash }) -join ''
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $fingerprint = [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($sourceHashes))).ToLowerInvariant()
    } finally { $sha.Dispose() }
    $result = [ordered]@{
        kind = 'offline-deterministic-regression'
        runId = [guid]::NewGuid().ToString()
        harnessVersion = $(if ($dirty) { "$version+working-tree" } else { $version })
        sourceFingerprint = $fingerprint
        timestampUtc = (Get-Date).ToUniversalTime().ToString('o')
        mavenExitCode = $mavenExit
        cases = $cases.Count
        passed = $passedCount
        successRate = $passedCount / $cases.Count
        metrics = [ordered]@{
            modelCalls = 'unavailable'; toolCalls = 'unavailable'
            tokenUsage = 'unavailable'; costPerTask = 'unavailable'
            recoveryRate = 'unavailable'; latencyP50 = 'unavailable'; latencyP95 = 'unavailable'
        }
        results = @($results)
    }
    if ($Baseline) {
        $old = Get-Content -Raw -LiteralPath (Join-Path $root $Baseline) | ConvertFrom-Json
        $oldIds = @($old.results | ForEach-Object id)
        $oldFailures = @($old.results | Where-Object { -not $_.passed } | ForEach-Object id)
        $newFailures = @($results | Where-Object { -not $_.passed } | ForEach-Object id)
        $result.comparableCases = @($results | Where-Object { $_.id -in $oldIds }).Count
        $result.newCases = @($results | Where-Object { $_.id -notin $oldIds } | ForEach-Object id)
        $result.regressions = @($newFailures | Where-Object { $_ -in $oldIds -and $_ -notin $oldFailures })
    }
    $destination = Join-Path $root $Output
    New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $destination -Encoding utf8
    Write-Output "$passedCount/$($cases.Count) passed; result: $destination"
    if ($mavenExit -ne 0 -or $passedCount -ne $cases.Count) { exit 1 }
} finally {
    Pop-Location
}
