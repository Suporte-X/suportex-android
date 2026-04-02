param(
    [string]$BackupRoot = "I:\Meu Drive\Suporte X\Backup do Projeto",
    [switch]$NoSnapshot
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidRepo = Split-Path -Parent $scriptDir
$webRepoDefault = Join-Path $env:USERPROFILE "Workspaces\SuporteX\web-servidor"
$memoryConfig = Join-Path $androidRepo ".codex-memory\config.json"

function Resolve-WebRepo {
    if (Test-Path $memoryConfig) {
        try {
            $cfg = Get-Content -Raw -Path $memoryConfig | ConvertFrom-Json
            if ($cfg.webRepo -and (Test-Path $cfg.webRepo)) {
                return $cfg.webRepo
            }
        } catch {
        }
    }
    return $webRepoDefault
}

function Ensure-Dir([string]$path) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory -Path $path -Force | Out-Null
    }
}

function Copy-DirectoryContents([string]$sourceDir, [string]$destDir) {
    if (-not (Test-Path $sourceDir)) { return $false }
    Ensure-Dir $destDir
    $items = Get-ChildItem -LiteralPath $sourceDir -Force -ErrorAction SilentlyContinue
    foreach ($item in $items) {
        Copy-Item -LiteralPath $item.FullName -Destination $destDir -Recurse -Force
    }
    return $true
}

function Copy-FileIfExists([string]$sourceFile, [string]$destFile) {
    if (-not (Test-Path $sourceFile)) { return $false }
    $parent = Split-Path -Parent $destFile
    Ensure-Dir $parent
    Copy-Item -LiteralPath $sourceFile -Destination $destFile -Force
    return $true
}

function Copy-EnvFiles([string]$repoPath, [string]$destDir) {
    Ensure-Dir $destDir
    $copied = 0
    $files = Get-ChildItem -LiteralPath $repoPath -Force -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq ".env" -or $_.Name -like ".env.*" }
    foreach ($f in $files) {
        Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $destDir $f.Name) -Force
        $copied++
    }
    return $copied
}

if (-not (Test-Path $BackupRoot)) {
    throw "Pasta de backup nao encontrada: $BackupRoot"
}

$webRepo = Resolve-WebRepo
$latestRoot = Join-Path $BackupRoot "latest"
$logRoot = Join-Path $BackupRoot "logs"
$snapshotRoot = Join-Path $BackupRoot ("snapshots\" + (Get-Date -Format "yyyyMMdd-HHmmss"))

Ensure-Dir $latestRoot
Ensure-Dir $logRoot
if (-not $NoSnapshot) { Ensure-Dir $snapshotRoot }

$targets = @()
$targets += [pscustomobject]@{
    label = "android-local.properties"
    source = Join-Path $androidRepo "local.properties"
    rel = "android\local.properties"
    type = "file"
}
$targets += [pscustomobject]@{
    label = "android-keystore.properties"
    source = Join-Path $androidRepo "keystore.properties"
    rel = "android\keystore.properties"
    type = "file"
}
$targets += [pscustomobject]@{
    label = "android-google-services.json"
    source = Join-Path $androidRepo "app\google-services.json"
    rel = "android\app\google-services.json"
    type = "file"
}
$targets += [pscustomobject]@{
    label = "android-codex-memory"
    source = Join-Path $androidRepo ".codex-memory"
    rel = "android\.codex-memory"
    type = "dir"
}
$targets += [pscustomobject]@{
    label = "web-secrets"
    source = Join-Path $webRepo ".secrets"
    rel = "web-servidor\.secrets"
    type = "dir"
}

$results = @()
foreach ($t in $targets) {
    $latestDest = Join-Path $latestRoot $t.rel
    $okLatest = $false
    $okSnapshot = $false

    if ($t.type -eq "file") {
        $okLatest = Copy-FileIfExists -sourceFile $t.source -destFile $latestDest
        if (-not $NoSnapshot) {
            $snapDest = Join-Path $snapshotRoot $t.rel
            $okSnapshot = Copy-FileIfExists -sourceFile $t.source -destFile $snapDest
        }
    } else {
        $okLatest = Copy-DirectoryContents -sourceDir $t.source -destDir $latestDest
        if (-not $NoSnapshot) {
            $snapDest = Join-Path $snapshotRoot $t.rel
            $okSnapshot = Copy-DirectoryContents -sourceDir $t.source -destDir $snapDest
        }
    }

    $results += [pscustomobject]@{
        item = $t.label
        source = $t.source
        latest = $okLatest
        snapshot = if ($NoSnapshot) { $null } else { $okSnapshot }
    }
}

$latestEnvDir = Join-Path $latestRoot "web-servidor"
$envLatestCount = Copy-EnvFiles -repoPath $webRepo -destDir $latestEnvDir
$envSnapshotCount = 0
if (-not $NoSnapshot) {
    $snapEnvDir = Join-Path $snapshotRoot "web-servidor"
    $envSnapshotCount = Copy-EnvFiles -repoPath $webRepo -destDir $snapEnvDir
}

$stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$logFile = Join-Path $logRoot ("backup-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")

@(
    "backup_at=$stamp"
    "backup_root=$BackupRoot"
    "android_repo=$androidRepo"
    "web_repo=$webRepo"
    "snapshot_enabled=" + (-not $NoSnapshot)
    "env_files_latest=$envLatestCount"
    "env_files_snapshot=$envSnapshotCount"
    ""
    "items:"
) | Set-Content -Path $logFile -Encoding UTF8

foreach ($r in $results) {
    Add-Content -Path $logFile -Value ("- {0} | source={1} | latest={2} | snapshot={3}" -f $r.item, $r.source, $r.latest, $r.snapshot)
}

Write-Output "Backup concluido."
Write-Output "Destino latest: $latestRoot"
if (-not $NoSnapshot) { Write-Output "Snapshot: $snapshotRoot" }
Write-Output "Log: $logFile"
