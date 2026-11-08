param(
    [string]$Ids = '',
    [string]$Output = 'target/harness-live/results.json',
    [switch]$Parallel,
    [string]$CasesFile = 'benchmarks/harness-live/cases.json',
    [switch]$CaptureContext
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    if (-not $env:JAVA_HOME -and (Test-Path 'C:\Users\l\.jdks\ms-17.0.18')) {
        $env:JAVA_HOME = 'C:\Users\l\.jdks\ms-17.0.18'
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    }
    $env:LANGFUSE_ENABLED = 'true'
    $env:PAICLI_LANGFUSE_ENABLED = 'true'
    $env:JAVA_TOOL_OPTIONS = '-Xmx512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2'
    $classpathFile = 'target/harness-live/classpath.txt'
    if (-not (Test-Path $classpathFile)) {
        & mvn dependency:build-classpath '-Dmdep.outputFile=target/harness-live/classpath.txt' -q
        if ($LASTEXITCODE -ne 0) { throw 'Could not build Java dependency classpath' }
    }
    $classpath = 'target/classes;' + (Get-Content -Raw -LiteralPath $classpathFile).Trim()
    $version = (& git rev-parse --short HEAD).Trim()
    if (-not [string]::IsNullOrWhiteSpace(((& git status --porcelain) -join "`n"))) {
        $version += '+working-tree'
    }
    New-Item -ItemType Directory -Force -Path 'target/harness-live/home' | Out-Null
    $homePath = (Resolve-Path 'target/harness-live/home').Path
    $executionMode = if ($Parallel) { 'parallel' } else { '' }
    $captureMode = if ($CaptureContext) { 'capture-context' } else { '' }
    & java '-Dfile.encoding=UTF-8' "-Duser.home=$homePath" '-Dpaicli.log.dir=target/harness-live/logs' "-Dpaicli.harness.version=$version" -cp $classpath com.paicli.eval.live.LiveHarnessMain $Output $Ids $executionMode $CasesFile $captureMode
    if ($LASTEXITCODE -ne 0) { throw "Live evaluation process exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
