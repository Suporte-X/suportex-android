param(
    [string]$AndroidRepoUrl = "https://github.com/Suporte-X/suportex-android.git",
    [string]$WebRepoUrl = "https://github.com/Suporte-X/suporte-x-servidor.git",
    [string]$AndroidPath = "$env:USERPROFILE\AndroidStudioProjects\SuporteX",
    [string]$WorkspaceRoot = "$env:USERPROFILE\Workspaces\SuporteX",
    [switch]$SkipFetch
)

$ErrorActionPreference = "Stop"

function Ensure-Git {
    $gitCmd = Get-Command git -ErrorAction SilentlyContinue
    if (-not $gitCmd) {
        throw "Git nao encontrado no PATH. Instale Git antes de continuar."
    }
}

function Ensure-Repo([string]$repoPath, [string]$repoUrl, [switch]$skipFetchUpdates) {
    $repoGitDir = Join-Path $repoPath ".git"
    if (Test-Path $repoGitDir) {
        if (-not $skipFetchUpdates) {
            git -C $repoPath fetch --all --prune
        }
        return
    }

    if (Test-Path $repoPath) {
        throw "Pasta existe, mas nao parece repo git: $repoPath"
    }

    $parent = Split-Path -Parent $repoPath
    if (-not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }

    git clone $repoUrl $repoPath
}

function Ensure-AndroidLink([string]$linkPath, [string]$targetPath) {
    if (Test-Path $linkPath) {
        $item = Get-Item -LiteralPath $linkPath -Force
        if ($item.LinkType -eq "Junction") {
            $targets = @($item.Target)
            if ($targets -contains $targetPath) {
                return
            }
            Write-Output "Aviso: junction ja existe com outro target: $linkPath"
            return
        }
        Write-Output "Aviso: caminho ja existe e nao e junction: $linkPath"
        return
    }

    New-Item -ItemType Junction -Path $linkPath -Target $targetPath | Out-Null
}

Ensure-Git

$webPath = Join-Path $WorkspaceRoot "web-servidor"
$androidLinkPath = Join-Path $WorkspaceRoot "android-app"
$memoryScript = Join-Path $AndroidPath "tools\codex-memory.ps1"

Ensure-Repo -repoPath $AndroidPath -repoUrl $AndroidRepoUrl -skipFetchUpdates:$SkipFetch
Ensure-Repo -repoPath $webPath -repoUrl $WebRepoUrl -skipFetchUpdates:$SkipFetch

if (-not (Test-Path $WorkspaceRoot)) {
    New-Item -ItemType Directory -Path $WorkspaceRoot -Force | Out-Null
}
Ensure-AndroidLink -linkPath $androidLinkPath -targetPath $AndroidPath

if (Test-Path $memoryScript) {
    & $memoryScript -Action configure -AndroidRepo $AndroidPath -WebRepo $webPath | Out-Null
}

Write-Output "Bootstrap de continuidade concluido."
Write-Output "androidRepo: $AndroidPath"
Write-Output "webRepo: $webPath"
Write-Output "androidLink: $androidLinkPath"
Write-Output "Proximo passo: execute 'tools\\check-recovery-readiness.ps1' no repo Android."
