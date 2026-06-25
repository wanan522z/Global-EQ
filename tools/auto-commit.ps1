param(
    [int]$IntervalSeconds = 5,
    [string]$MessagePrefix = "auto"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Test-GitMarker {
    param([string]$Path)
    return Test-Path (Join-Path $repoRoot ".git\$Path")
}

Write-Host "Auto-commit is watching $repoRoot"
Write-Host "Polling every $IntervalSeconds seconds. Push remains manual."
Write-Host "Press Ctrl+C to stop."

while ($true) {
    try {
        if (
            (Test-GitMarker "MERGE_HEAD") -or
            (Test-GitMarker "REBASE_HEAD") -or
            (Test-GitMarker "CHERRY_PICK_HEAD") -or
            (Test-GitMarker "BISECT_LOG")
        ) {
            Start-Sleep -Seconds $IntervalSeconds
            continue
        }

        git add -A | Out-Null
        git diff --cached --quiet

        if ($LASTEXITCODE -ne 0) {
            $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
            $message = "${MessagePrefix}: $timestamp"
            git commit -m $message | Out-Host
        }
    } catch {
        Write-Warning $_
    }

    Start-Sleep -Seconds $IntervalSeconds
}
