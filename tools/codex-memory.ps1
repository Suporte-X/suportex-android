param(
    [ValidateSet("start", "append", "session", "status", "configure")]
    [string]$Action = "start",
    [string]$Text = "",
    [string]$AndroidRepo = "",
    [string]$WebRepo = ""
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$memoryRoot = Join-Path $projectRoot ".codex-memory"
$sessionsDir = Join-Path $memoryRoot "sessions"
$stateFile = Join-Path $memoryRoot "state.md"
$configFile = Join-Path $memoryRoot "config.json"

function Get-DefaultWebRepo {
    $candidates = @(
        $env:SUPORTEX_WEB_REPO,
        (Join-Path $env:USERPROFILE "Workspaces\SuporteX\web-servidor"),
        (Join-Path (Split-Path -Parent $projectRoot) "SuporteX\web-servidor")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    return (Join-Path $env:USERPROFILE "Workspaces\SuporteX\web-servidor")
}

function Resolve-RepoPath([string]$pathValue) {
    if ([string]::IsNullOrWhiteSpace($pathValue)) { return "" }
    $withRoot = $pathValue.Replace('${PROJECT_ROOT}', $projectRoot)
    if ([System.IO.Path]::IsPathRooted($withRoot)) { return $withRoot }
    return (Join-Path $projectRoot $withRoot)
}

function Save-Config([string]$androidRepoPath, [string]$webRepoPath) {
    if (-not (Test-Path $memoryRoot)) { New-Item -ItemType Directory -Path $memoryRoot | Out-Null }
    $config = [ordered]@{
        androidRepo = $androidRepoPath
        webRepo = $webRepoPath
    }
    $json = $config | ConvertTo-Json
    Set-Content -Path $configFile -Value $json -Encoding UTF8
}

function Ensure-MemoryStructure {
    if (-not (Test-Path $memoryRoot)) { New-Item -ItemType Directory -Path $memoryRoot | Out-Null }
    if (-not (Test-Path $sessionsDir)) { New-Item -ItemType Directory -Path $sessionsDir | Out-Null }
    if (-not (Test-Path $stateFile)) {
        @"
# Estado Consolidado - SupportX

Atualizado em: $(Get-Date -Format "yyyy-MM-dd")
"@ | Set-Content -Path $stateFile -Encoding UTF8
    }
    if (-not (Test-Path $configFile)) {
        Save-Config -androidRepoPath $projectRoot -webRepoPath (Get-DefaultWebRepo)
    }
}

function Get-Config {
    Ensure-MemoryStructure
    $raw = Get-Content -Raw -Path $configFile | ConvertFrom-Json
    return [pscustomobject]@{
        androidRepo = Resolve-RepoPath $raw.androidRepo
        webRepo = Resolve-RepoPath $raw.webRepo
    }
}

function Get-GitSummary([string]$repoPath, [string]$label) {
    if (-not (Test-Path $repoPath)) {
        return "[$label] repo nao encontrado: $repoPath"
    }
    try {
        $branch = git -C $repoPath rev-parse --abbrev-ref HEAD 2>$null
        if ($LASTEXITCODE -ne 0) { throw "git indisponivel" }
        $head = git -C $repoPath log -1 --pretty=format:"%h %ad %s" --date=short 2>$null
        $recent = git -C $repoPath log -3 --pretty=format:"- %h %ad %s" --date=short 2>$null
    } catch {
        return "[$label] nao foi possivel ler git em: $repoPath"
    }
    return @"
[$label]
Branch: $branch
HEAD: $head
Recentes:
$recent
"@
}

function Get-LatestSessions([int]$count = 5) {
    if (-not (Test-Path $sessionsDir)) { return "Nenhuma sessao registrada ainda." }
    $files = Get-ChildItem -Path $sessionsDir -File | Sort-Object LastWriteTime -Descending | Select-Object -First $count
    if (-not $files) { return "Nenhuma sessao registrada ainda." }
    $lines = @()
    foreach ($f in $files) {
        $lines += "- $($f.Name)"
    }
    return ($lines -join [Environment]::NewLine)
}

Ensure-MemoryStructure
$cfg = Get-Config

switch ($Action) {
    "start" {
        Write-Output "===== CODEX MEMORY START ====="
        Write-Output ""
        Write-Output "Estado consolidado:"
        Get-Content -Path $stateFile
        Write-Output ""
        Write-Output "Ultimas sessoes:"
        Write-Output (Get-LatestSessions -count 5)
        Write-Output ""
        Write-Output (Get-GitSummary -repoPath $cfg.androidRepo -label "Android")
        Write-Output ""
        Write-Output (Get-GitSummary -repoPath $cfg.webRepo -label "Web-Servidor")
    }
    "configure" {
        $newAndroid = if ([string]::IsNullOrWhiteSpace($AndroidRepo)) { $projectRoot } else { Resolve-RepoPath $AndroidRepo }
        $newWeb = if ([string]::IsNullOrWhiteSpace($WebRepo)) { Get-DefaultWebRepo } else { Resolve-RepoPath $WebRepo }
        Save-Config -androidRepoPath $newAndroid -webRepoPath $newWeb
        Write-Output "Configuracao salva: $configFile"
        Write-Output "androidRepo=$newAndroid"
        Write-Output "webRepo=$newWeb"
    }
    "append" {
        if ([string]::IsNullOrWhiteSpace($Text)) {
            throw "Use -Text para adicionar conteudo ao state.md"
        }
        Add-Content -Path $stateFile -Value ""
        Add-Content -Path $stateFile -Value $Text
        Add-Content -Path $stateFile -Value ""
        Add-Content -Path $stateFile -Value ("Atualizado em: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
        Write-Output "Atualizacao adicionada em $stateFile"
    }
    "session" {
        if ([string]::IsNullOrWhiteSpace($Text)) {
            throw "Use -Text com resumo curto da sessao."
        }
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $sessionFile = Join-Path $sessionsDir "$stamp.md"
        @"
# Sessao $stamp

Data: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")

Resumo:
$Text
"@ | Set-Content -Path $sessionFile -Encoding UTF8
        Write-Output "Sessao registrada: $sessionFile"
    }
    "status" {
        Write-Output "state: $stateFile"
        Write-Output "sessions: $sessionsDir"
        Write-Output ("total_sessions: " + (Get-ChildItem -Path $sessionsDir -File -ErrorAction SilentlyContinue | Measure-Object).Count)
    }
}
