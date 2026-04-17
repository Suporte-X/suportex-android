# Suporte X - Continuidade e Recuperacao

Atualizado em: 2026-04-06

## Objetivo

Garantir que o projeto possa ser retomado em outra maquina sem copia manual de HD, usando GitHub + um bootstrap simples.

## Repositorios oficiais

- Android: `https://github.com/Suporte-X/suportex-android.git`
- Web/Servidor: `https://github.com/Suporte-X/suporte-x-servidor.git`

## Estrutura recomendada no Windows

- `%USERPROFILE%\AndroidStudioProjects\SuporteX` (repo Android)
- `%USERPROFILE%\Workspaces\SuporteX\web-servidor` (repo Web/Servidor)
- `%USERPROFILE%\Workspaces\SuporteX\android-app` (junction para o repo Android)

## Fronteira de codigo (padrao obrigatorio)

- No repo Android: somente codigo/app Android e scripts de continuidade local.
- No repo Web/Servidor: somente backend Web, central web e documentacao web.
- Nao reintroduzir `tech-panel` legado e docs de backend dentro do repo Android.
- Nao reintroduzir placeholder `android/` dentro do repo Web/Servidor.

## Itens que NAO sobem para GitHub (precisam de backup separado)

- `%USERPROFILE%\AndroidStudioProjects\SuporteX\local.properties`
- `%USERPROFILE%\Workspaces\SuporteX\web-servidor\.secrets\firebase-admin.json`
- Qualquer `.env*` local e credencial privada
- Conteudo de `.codex-memory/` (memoria local do Codex)

## Backup no Google Drive (automatico)

Pasta usada:

- `I:\Meu Drive\Suporte X\Backup do Projeto`

Scripts:

- `tools/backup-sensitive-to-drive.ps1`: copia segredos e memoria para `latest/` e cria snapshot com data/hora.
- `tools/register-backup-task.ps1`: agenda o backup no Windows (padrao: a cada 6 horas).

Execucao manual imediata:

```powershell
& .\tools\backup-sensitive-to-drive.ps1
```

Agendar automatico:

```powershell
& .\tools\register-backup-task.ps1
```

## Bootstrap em maquina nova

No PowerShell:

```powershell
& "$env:USERPROFILE\AndroidStudioProjects\SuporteX\tools\bootstrap-recovery.ps1"
```

O script:

- clona/atualiza os dois repositorios;
- prepara `Workspaces\SuporteX`;
- cria a junction `android-app`;
- configura o `tools\codex-memory.ps1` com os caminhos corretos.

## Checklist rapido de prontidao

No repo Android:

```powershell
& .\tools\check-recovery-readiness.ps1
```

Interpretacao:

- `OK` em tudo: retomada pronta.
- `WARN`: existe divergencia local, arquivos sem commit, ou segredo local ausente.

## Regra de ouro de continuidade

Antes de encerrar um bloco tecnico importante:

1. Validar com `tools\check-recovery-readiness.ps1`.
2. Garantir que o que precisa ir para nuvem esteja commitado e em `origin/main`.
3. Registrar sessao em `.codex-memory` com resumo tecnico e pontos de nao regressao.

## Nao regressao

- Nao alterar funcionamento de app/servidor ao ajustar apenas docs/scripts de continuidade.
- Nao subir `.codex-memory/` nem segredos locais.
- Manter `AGENTS.md` e scripts de memoria alinhados com este documento.
