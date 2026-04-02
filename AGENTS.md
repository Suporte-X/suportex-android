# SupportX - Protocolo de Memoria Codex

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
