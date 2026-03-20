# SuporteX Secure Upload Backend

Endpoints implementados:

- `POST /api/upload/session-attachment`
- `POST /api/upload/session-audio`
- `POST /api/upload/avatar`

## Regras de seguranca aplicadas

- `Authorization: Bearer <Firebase ID Token>` obrigatorio
- token validado com Firebase Admin (`verifyIdToken`)
- upload de sessao permitido somente para membro da sessao (`clientUid` ou `techUid`)
- validacao de MIME e tamanho por endpoint
- nome de arquivo seguro gerado no backend
- upload feito via Admin SDK para Firebase Storage
- metadata de upload registrada no Firestore (`sessions/{sessionId}/uploads/{uploadId}` e `techs/{uid}/uploads/{uploadId}`)

## Variaveis de ambiente esperadas

- `GOOGLE_APPLICATION_CREDENTIALS` (ou credenciais equivalentes do ambiente Render)
- `FIREBASE_STORAGE_BUCKET`
- `PORT` (opcional)

## Execucao local

```bash
cd backend
npm install
npm test
npm start
```
