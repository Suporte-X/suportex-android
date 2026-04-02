# SuporteX - Continuidade e Recuperacao

Atualizado em: 2026-04-02

## Objetivo

Garantir que o projeto possa ser retomado em outra maquina sem copia manual de HD, usando GitHub + um bootstrap simples.

## Repositorios oficiais

- Android: `https://github.com/Suporte-X/suportex-android.git`
- Web/Servidor: `https://github.com/Suporte-X/suporte-x-servidor.git`

## Estrutura recomendada no Windows

- `%USERPROFILE%\AndroidStudioProjects\SuporteX` (repo Android)
- `%USERPROFILE%\Workspaces\SuporteX\web-servidor` (repo Web/Servidor)
- `%USERPROFILE%\Workspaces\SuporteX\android-app` (junction para o repo Android)

## Itens que NAO sobem para GitHub (precisam de backup separado)

- `%USERPROFILE%\AndroidStudioProjects\SuporteX\local.properties`
- `%USERPROFILE%\Workspaces\SuporteX\web-servidor\.secrets\firebase-admin.json`
- Qualquer `.env*` local e credencial privada
- Conteudo de `.codex-memory/` (memoria local do Codex)

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
