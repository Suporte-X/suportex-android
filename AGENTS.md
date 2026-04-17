# Suporte X - Protocolo de Memoria Codex

Este repositorio usa memoria local para reduzir retrabalho entre chats.

## Regra obrigatoria no inicio da tarefa

Executar:

```powershell
& C:\Users\X-Not\AndroidStudioProjects\SuporteX\tools\codex-memory.ps1 -Action start
```

Usar a saida como contexto obrigatorio da sessao.

## Regra obrigatoria no encerramento da tarefa tecnica

Registrar sessao automaticamente:

```powershell
& C:\Users\X-Not\AndroidStudioProjects\SuporteX\tools\codex-memory.ps1 -Action session -Text "<resumo curto da sessao>"
```

O resumo deve conter, no minimo:

- o que foi alterado;
- o que foi validado (build/test/deploy/monitoramento);
- pendencias;
- pontos de nao regressao.

## Politica de commit

- `.codex-memory/` deve permanecer local (nao subir para GitHub).
- So comitar arquivos de codigo/config quando solicitado.

## Regra de ouro de continuidade (DR)

- Nao depender de caminho absoluto antigo para retomar em outra maquina.
- Antes de encerrar mudancas tecnicas relevantes, rodar:

```powershell
& C:\Users\X-Not\AndroidStudioProjects\SuporteX\tools\check-recovery-readiness.ps1
```

- Garantir que os dois repositorios oficiais estejam sincronizados com `origin/main` quando o objetivo for checkpoint em nuvem.
- Nao remover os scripts `tools/codex-memory.ps1`, `tools/bootstrap-recovery.ps1` e `tools/check-recovery-readiness.ps1`.

## Regra de ouro de fronteira entre repositorios (obrigatoria)

- Aplicar separacao por dominio mesmo sem solicitacao explicita do usuario.
- Codigo Android/mobile deve permanecer no repo Android (`suportex-android`).
- Codigo Web/Servidor (HTML/JS/CSS, backend Node, regras Firebase, docs web) deve permanecer no repo Web/Servidor (`suporte-x-servidor`).
- Ao detectar arquivo em repositorio incorreto, mover para o repositorio correto antes de finalizar a tarefa.
- Antes de encerrar checkpoint em nuvem, confirmar estruturalmente:
  - repo Android: `main == origin/main` (0 ahead / 0 behind), remoto `suportex-android`;
  - repo Web/Servidor: `main == origin/main` (0 ahead / 0 behind), remoto `suporte-x-servidor`.

## Mapa operacional atual (informativo)

- Repo Android oficial: `C:\Users\X-Not\AndroidStudioProjects\SuporteX`
- Repo Web/Servidor oficial: `C:\Users\X-Not\Workspaces\SuporteX\web-servidor`
- Alias Android em workspace: `C:\Users\X-Not\Workspaces\SuporteX\android-app` (junction para o repo Android oficial)
- Documento de referencia: `docs/continuidade-e-recuperacao.md`
