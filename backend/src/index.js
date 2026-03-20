const express = require('express');
const admin = require('firebase-admin');
const { createUploadRouter } = require('./uploadRouter');

function ensureFirebaseAdmin() {
  if (!admin.apps.length) {
    const storageBucket = process.env.FIREBASE_STORAGE_BUCKET;
    admin.initializeApp(storageBucket ? { storageBucket } : undefined);
  }
  return admin;
}

function createApp() {
  const firebase = ensureFirebaseAdmin();
  const app = express();
  app.disable('x-powered-by');

  app.get('/health', (_req, res) => {
    res.status(200).json({ ok: true, service: 'suportex-upload' });
  });

  app.use('/api/upload', createUploadRouter({
    auth: firebase.auth(),
    db: firebase.firestore(),
    bucket: firebase.storage().bucket(),
    logger: console,
  }));

  return app;
}

if (require.main === module) {
  const app = createApp();
  const port = Number(process.env.PORT || 3000);
  app.listen(port, () => {
    console.log(`[upload] listening on ${port}`);
  });
}

module.exports = { createApp, ensureFirebaseAdmin };
