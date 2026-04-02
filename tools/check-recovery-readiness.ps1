param(
    [string]$AndroidRepo = "",
    [string]$WebRepo = "",
    [switch]$FailOnWarning
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$configPath = Join-Path $projectRoot ".codex-memory\config.json"

function Resolve-PathValue([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { return "" }
    if ([System.IO.Path]::IsPathRooted($value)) { return $value }
    return (Join-Path $projectRoot $value)
}

function Get-ConfiguredPaths {
    if (-not (Test-Path $configPath)) {
        return [pscustomobject]@{
            androidRepo = $projectRoot
            webRepo = "$env:USERPROFILE\Workspaces\SuporteX\web-servidor"
        }
    }
    $raw = Get-Content -Raw -Path $configPath | ConvertFrom-Json
    return [pscustomobject]@{
        androidRepo = Resolve-PathValue $raw.androidRepo
        webRepo = Resolve-PathValue $raw.webRepo
    }
}

function Get-RepoStatus([string]$label, [string]$repoPath) {
    if (-not (Test-Path $repoPath)) {
        return [pscustomobject]@{
            label = $label
            path = $repoPath
            ok = $false
            notes = @("repo nao encontrado")
        }
    }

    if (-not (Test-Path (Join-Path $repoPath ".git"))) {
        return [pscustomobject]@{
            label = $label
            path = $repoPath
            ok = $false
            notes = @("pasta existe, mas nao e repo git")
        }
    }

    $notes = @()
    $ok = $true

    $branch = git -C $repoPath rev-parse --abbrev-ref HEAD 2>$null
    if ($LASTEXITCODE -ne 0) {
        $notes += "falha ao obter branch"
        $ok = $false
    } else {
        $notes += "branch atual: $branch"
    }

    $divRaw = git -C $repoPath rev-list --left-right --count origin/main...main 2>$null
    if ($LASTEXITCODE -eq 0 -and $divRaw) {
        $parts = $divRaw -split "\s+"
        if ($parts.Length -ge 2) {
            $behind = [int]$parts[0]
            $ahead = [int]$parts[1]
            $notes += "divergencia com origin/main: behind=$behind ahead=$ahead"
            if ($behind -gt 0 -or $ahead -gt 0) { $ok = $false }
        }
    } else {
        $notes += "nao foi possivel comparar com origin/main"
        $ok = $false
    }

    $dirty = @(git -C $repoPath status --porcelain)
    $dirtyCount = $dirty.Count
    $notes += "alteracoes locais pendentes: $dirtyCount"
    if ($dirtyCount -gt 0) { $ok = $false }

    return [pscustomobject]@{
        label = $label
        path = $repoPath
        ok = $ok
        notes = $notes
    }
}

function Get-FileCheck([string]$label, [string]$filePath) {
    $exists = Test-Path $filePath
    $note = "arquivo ausente"
    if ($exists) {
        $note = "arquivo presente"
    }
    return [pscustomobject]@{
        label = $label
        path = $filePath
        ok = $exists
        notes = @($note)
    }
}

$cfg = Get-ConfiguredPaths
$androidPath = if ([string]::IsNullOrWhiteSpace($AndroidRepo)) { $cfg.androidRepo } else { Resolve-PathValue $AndroidRepo }
$webPath = if ([string]::IsNullOrWhiteSpace($WebRepo)) { $cfg.webRepo } else { Resolve-PathValue $WebRepo }

$checks = @()
$checks += Get-RepoStatus -label "Android repo" -repoPath $androidPath
$checks += Get-RepoStatus -label "Web repo" -repoPath $webPath
$checks += Get-FileCheck -label "Android google-services.json" -filePath (Join-Path $androidPath "app\google-services.json")
$checks += Get-FileCheck -label "Web firebase-admin.json" -filePath (Join-Path $webPath ".secrets\firebase-admin.json")

$warnings = 0

Write-Output "===== RECOVERY READINESS ====="
foreach ($check in $checks) {
    $status = if ($check.ok) { "OK" } else { "WARN" }
    if (-not $check.ok) { $warnings++ }
    Write-Output ""
    Write-Output "[$status] $($check.label)"
    Write-Output "path: $($check.path)"
    foreach ($note in $check.notes) {
        Write-Output "- $note"
    }
}

Write-Output ""
Write-Output "Resumo: total_checks=$($checks.Count) warnings=$warnings"

if ($FailOnWarning -and $warnings -gt 0) {
    exit 1
}
