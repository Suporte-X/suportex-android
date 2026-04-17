param(
    [string]$TaskName = "Suporte X-Backup-Projeto",
    [string]$BackupRoot = "I:\Meu Drive\Suporte X\Backup do Projeto",
    [string]$BackupAliasRoot = "C:\SuporteXBackupDrive",
    [int]$EveryHours = 6
)

$ErrorActionPreference = "Stop"

if ($EveryHours -lt 1) {
    throw "EveryHours deve ser >= 1."
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$backupScript = Join-Path $scriptDir "backup-sensitive-to-drive.ps1"

if (-not (Test-Path $backupScript)) {
    throw "Script de backup nao encontrado: $backupScript"
}

if (-not (Test-Path $BackupRoot)) {
    throw "Pasta de backup nao encontrada: $BackupRoot"
}

if (Test-Path $BackupAliasRoot) {
    $item = Get-Item -LiteralPath $BackupAliasRoot -Force
    if ($item.LinkType -eq "Junction") {
        $targets = @($item.Target)
        if (-not ($targets -contains $BackupRoot)) {
            throw "Junction existente em $BackupAliasRoot aponta para outro destino."
        }
    }
} else {
    New-Item -ItemType Junction -Path $BackupAliasRoot -Target $BackupRoot | Out-Null
}

$powerShellExe = "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
$taskCommand = "$powerShellExe -NoProfile -ExecutionPolicy Bypass -File $backupScript -BackupRoot $BackupAliasRoot"

# Cria ou atualiza tarefa recorrente a cada X horas usando caminho sem espacos/acento.
schtasks /Create /TN $TaskName /TR $taskCommand /SC HOURLY /MO $EveryHours /F | Out-Null

# Executa uma vez agora para validar.
schtasks /Run /TN $TaskName | Out-Null

Write-Output "Tarefa agendada com sucesso."
Write-Output "task_name=$TaskName"
Write-Output "frequency=every_${EveryHours}h"
Write-Output "backup_root=$BackupRoot"
Write-Output "backup_alias=$BackupAliasRoot"
Write-Output "command=$taskCommand"
