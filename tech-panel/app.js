import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth,
  onAuthStateChanged,
  signInAnonymously
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  addDoc,
  collection,
  doc,
  getDoc,
  getDocs,
  getFirestore,
  query,
  runTransaction,
  setDoc,
  Timestamp,
  updateDoc,
  where
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";

const COLLECTIONS = {
  clients: "clients",
  clientProfiles: "client_profiles",
  clientAppLinks: "client_app_links",
  clientVerifications: "client_verifications",
  pnvRequests: "pnv_requests",
  supportSessions: "support_sessions",
  supportReports: "support_reports",
  creditOrders: "credit_orders",
  creditPackages: "credit_packages"
};

const RETENTION_DAYS = {
  pnvRequests: 15,
  supportSessions: 30,
  supportReports: 30
};

const state = {
  selectedClientId: null,
  selectedPhone: null,
  packages: [],
  draftMode: "empty",
  clientOriginal: null,
  clientDraft: null,
  profileNotice: null,
  profileContext: null,
  sections: {
    verification: false
  }
};

const firebaseConfig = window.SUPORTEX_FIREBASE_CONFIG;
const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);

const authStatus = document.querySelector("#auth-status");
const queueList = document.querySelector("#queue-list");
const historyList = document.querySelector("#history-list");
const ordersList = document.querySelector("#orders-list");
const profileContainer = document.querySelector("#client-profile");
const refreshAllBtn = document.querySelector("#refresh-all");
const searchClientBtn = document.querySelector("#search-client-btn");
const searchPhoneInput = document.querySelector("#search-phone");
const newClientBtn = document.querySelector("#new-client-btn");

setup();

async function setup() {
  bindActions();
  renderEmptyProfile();
  try {
    await signInAnonymously(auth);
  } catch (err) {
    console.error(err);
    authStatus.textContent = "Falha de autenticação";
  }
  onAuthStateChanged(auth, async (user) => {
    authStatus.textContent = user ? `Conectado: ${user.uid.slice(0, 8)}...` : "Sem autenticação";
    await loadAll();
  });
}

function bindActions() {
  refreshAllBtn.addEventListener("click", () => {
    loadAll();
  });

  searchClientBtn.addEventListener("click", async () => {
    await openClientFromSearch();
  });

  newClientBtn.addEventListener("click", async () => {
    beginNewClientDraft(searchPhoneInput.value);
    setProfileNotice("info", "Preencha os dados para criar uma nova ficha.");
    renderProfileFromState();
    await loadOrders();
  });

  searchPhoneInput.addEventListener("keydown", async (event) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    await openClientFromSearch();
  });
}

async function openClientFromSearch() {
  const normalized = normalizePhone(searchPhoneInput.value);
  if (!normalized) {
    alert("Digite um telefone válido.");
    return;
  }
  const clientId = await resolveClientIdByPhone(normalized);
  if (!clientId) {
    beginNewClientDraft(normalized);
    setProfileNotice("info", "Cliente não encontrado. Cadastro pronto para preenchimento.");
    renderProfileFromState();
    await loadOrders();
    return;
  }
  await loadClientProfile(clientId);
}

async function loadAll() {
  await Promise.all([
    loadPackages(),
    loadQueue(),
    loadOrders()
  ]);

  if (state.selectedClientId) {
    await loadClientProfile(state.selectedClientId);
    return;
  }

  if (state.draftMode === "new") {
    renderProfileFromState();
    return;
  }

  renderEmptyProfile();
}

async function loadPackages() {
  const snap = await getDocs(collection(db, COLLECTIONS.creditPackages));
  state.packages = snap.docs
    .map((item) => ({ id: item.id, ...item.data() }))
    .filter((pkg) => pkg.active !== false)
    .sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0));
}
async function loadQueue() {
  queueList.innerHTML = "<small>Carregando...</small>";
  const snap = await getDocs(collection(db, COLLECTIONS.supportSessions));
  const sessions = snap.docs
    .map((docItem) => ({ id: docItem.id, ...docItem.data() }))
    .filter((item) => item.status === "queued" || item.status === "in_progress")
    .sort((a, b) => (b.startedAt ?? 0) - (a.startedAt ?? 0))
    .slice(0, 40);

  const rows = await Promise.all(
    sessions.map(async (session) => {
      const clientId = session.clientId || null;
      if (!clientId) {
        return { session, client: null, verification: null };
      }
      const clientSnap = await getDoc(doc(db, COLLECTIONS.clients, clientId));
      const verificationSnap = await getDoc(doc(db, COLLECTIONS.clientVerifications, clientId));
      return {
        session,
        client: clientSnap.exists() ? { id: clientSnap.id, ...clientSnap.data() } : null,
        verification: verificationSnap.exists() ? verificationSnap.data() : null
      };
    })
  );

  if (!rows.length) {
    queueList.innerHTML = "<small>Sem solicitações pendentes.</small>";
    return;
  }

  queueList.innerHTML = "";
  rows.forEach(({ session, client, verification }) => {
    const isNewClient = !client;
    const freePending = isNewClient ? true : client.freeFirstSupportUsed === false;
    const noCredit = !freePending && (client?.credits ?? 0) <= 0;
    const verificationStatus = verification?.status || "pending";

    const card = document.createElement("article");
    card.className = `queue-item ${isNewClient ? "is-new" : ""} ${noCredit ? "no-credit" : ""}`;
    card.innerHTML = `
      <div class="queue-item-top">
        <div>
          <strong>${escapeHtml(client?.name || "Cliente não cadastrado")}</strong>
          <div class="queue-phone">${escapeHtml(session.clientPhone || client?.phone || "-")}</div>
        </div>
        <span class="badge ${session.status === "in_progress" ? "ok" : "warn"}">
          ${escapeHtml(sessionStatusLabel(session.status))}
        </span>
      </div>
      <div class="queue-meta">
        ${isNewClient ? '<span class="badge warn">Primeiro atendimento</span>' : ""}
        ${noCredit ? '<span class="badge danger">Sem crédito</span>' : ""}
        ${!isNewClient && !noCredit ? '<span class="badge ok">Com crédito</span>' : ""}
        <span class="badge ${verificationBadgeClass(verificationStatus)}">${escapeHtml(verificationStatusLabel(verificationStatus))}</span>
      </div>
      <div class="queue-note">Abra a ficha para atualizar dados, validar atendimento e seguir com a ação necessária.</div>
      <div class="profile-actions compact">
        <button type="button" class="accent-btn" data-open-client="${client?.id || ""}" data-phone="${session.clientPhone || ""}">
          ${client?.id ? "Abrir ficha" : "Cadastrar cliente"}
        </button>
      </div>
    `;

    const openBtn = card.querySelector("button[data-open-client]");
    openBtn.addEventListener("click", async () => {
      if (client?.id) {
        await loadClientProfile(client.id);
        return;
      }

      beginNewClientDraft(openBtn.dataset.phone || "");
      setProfileNotice("info", "Solicitação sem cadastro encontrado. Complete a ficha para criar o cliente.");
      renderProfileFromState();
      await loadOrders();
    });

    queueList.appendChild(card);
  });
}

async function loadClientProfile(clientId) {
  const clientSnap = await getDoc(doc(db, COLLECTIONS.clients, clientId));
  if (!clientSnap.exists()) {
    state.selectedClientId = null;
    state.selectedPhone = null;
    state.draftMode = "empty";
    state.clientDraft = null;
    state.clientOriginal = null;
    state.profileContext = null;
    renderEmptyProfile("Cliente não encontrado.");
    historyList.innerHTML = "";
    return;
  }

  const client = { id: clientSnap.id, ...clientSnap.data() };
  const profileSnap = await getDoc(doc(db, COLLECTIONS.clientProfiles, client.id));
  const profile = profileSnap.exists() ? profileSnap.data() : null;
  const verificationSnap = await getDoc(doc(db, COLLECTIONS.clientVerifications, client.id));
  const verification = verificationSnap.exists() ? verificationSnap.data() : null;
  const latestPnvRequest = await getLatestPnvRequestForClient(client.id);
  const activeSession = await getLatestOpenSessionForClient(client.id);

  state.selectedClientId = client.id;
  state.selectedPhone = client.phone || null;
  state.draftMode = "existing";
  state.clientOriginal = buildDraftFromClient(client);
  state.clientDraft = { ...state.clientOriginal };
  state.sections.verification = verification?.status !== "verified";

  renderClientProfile(client, profile, verification, latestPnvRequest, activeSession);
  await loadHistory(client.id);
  await loadOrders(client.id);
}

function beginNewClientDraft(phone = "") {
  const normalized = normalizePhone(phone) || String(phone || "").trim();
  state.selectedClientId = null;
  state.selectedPhone = normalized || null;
  state.draftMode = "new";
  state.clientOriginal = {
    name: "",
    phone: normalized || "",
    email: "",
    notes: ""
  };
  state.clientDraft = { ...state.clientOriginal };
  state.profileContext = {
    client: null,
    profile: null,
    verification: null,
    latestPnvRequest: null,
    activeSession: null
  };
  historyList.innerHTML = "<small>O histórico aparecerá após o primeiro cadastro e atendimento.</small>";
}

function renderProfileFromState() {
  const context = state.profileContext;
  if (!context) {
    renderEmptyProfile();
    return;
  }

  renderClientProfile(
    context.client,
    context.profile,
    context.verification,
    context.latestPnvRequest,
    context.activeSession
  );
}

function renderEmptyProfile(message = "Selecione um cliente da fila ou busque por telefone.") {
  profileContainer.className = "profile-empty";
  profileContainer.innerHTML = `
    <div class="profile-empty-shell">
      <span class="eyebrow">Workspace do atendimento</span>
      <h3>Ficha pronta para consulta, cadastro e ajustes rápidos.</h3>
      <p>${escapeHtml(message)}</p>
      <div class="empty-tips">
        <div>
          <strong>Buscar cliente</strong>
          <span>Use o telefone para abrir a ficha existente.</span>
        </div>
        <div>
          <strong>Novo cadastro</strong>
          <span>Se não existir registro, iniciamos a ficha com os dados mínimos.</span>
        </div>
        <div>
          <strong>Menos ruído</strong>
          <span>Ações sensíveis ficam recolhidas até quando fizerem sentido.</span>
        </div>
      </div>
    </div>
  `;
}
function renderClientProfile(client, profile, verification, latestPnvRequest, activeSession) {
  state.profileContext = {
    client,
    profile,
    verification,
    latestPnvRequest,
    activeSession
  };

  const draft = state.clientDraft || buildDraftFromClient(client);
  const isNewDraft = !client && state.draftMode === "new";
  const verificationStatus = verification?.status || "pending";
  const latestPnvText = latestPnvRequest
    ? `${pnvStatusLabel(latestPnvRequest.status)} em ${formatDate(latestPnvRequest.updatedAt || latestPnvRequest.createdAt)}`
    : "Sem solicitação recente";
  const verificationReason = verification?.mismatchReason || latestPnvRequest?.reason || "Sem observação técnica";
  const financialStatus = client
    ? !client.freeFirstSupportUsed
      ? "Primeiro atendimento gratuito disponível"
      : (client.credits ?? 0) > 0
        ? "Com crédito disponível"
        : "Sem crédito disponível"
    : "Cadastro pendente";
  const primaryAction = getPrimaryActionMeta();
  const verificationExpanded = Boolean(client) && (
    state.sections.verification
    || verificationStatus !== "verified"
    || latestPnvRequest?.status === "manual_pending"
  );
  const headline = draft.name || (client?.name || "Novo cadastro");
  const contextLine = activeSession
    ? `Sessão ${activeSession.id.slice(0, 8)} • ${sessionStatusLabel(activeSession.status)}`
    : isNewDraft
      ? "Preencha os dados principais para criar a ficha."
      : "Sem sessão/chamado ativo.";

  profileContainer.className = "profile-mounted";
  profileContainer.innerHTML = `
    <div class="client-shell">
      <div class="client-hero">
        <div class="client-hero-copy">
          <span class="eyebrow">${isNewDraft ? "Novo cadastro" : "Ficha do cliente"}</span>
          <h3 id="profile-hero-title">${escapeHtml(headline)}</h3>
          <p>${escapeHtml(contextLine)}</p>
        </div>
        <div class="hero-status">
          <span class="status-pill ${client ? financialStatusTone(client) : "info"}">${escapeHtml(financialStatus)}</span>
          <span class="status-pill ${verificationBadgeClass(verificationStatus)}">${escapeHtml(verificationStatusLabel(verificationStatus))}</span>
          ${client ? `<span class="status-pill subtle">${escapeHtml(client.id)}</span>` : ""}
        </div>
      </div>

      ${renderProfileNotice()}

      <div class="client-workspace">
        <aside class="client-summary">
          <div class="summary-card">
            <span class="summary-kicker">Contato principal</span>
            <strong id="summary-phone">${escapeHtml(normalizePhone(draft.phone) || draft.phone || "Telefone não informado")}</strong>
            <span id="summary-email">${escapeHtml(draft.email || "Sem e-mail principal")}</span>
          </div>

          <div class="summary-stats">
            ${renderSummaryStat("Créditos", client ? client.credits ?? 0 : "—")}
            ${renderSummaryStat("Atendimentos usados", client ? client.supportsUsed ?? 0 : "—")}
            ${renderSummaryStat("Total de sessões", client ? profile?.totalSessions ?? 0 : "—")}
            ${renderSummaryStat("Primeiro suporte", client ? (client.freeFirstSupportUsed ? "Já usado" : "Disponível") : "Pendente")}
            ${renderSummaryStat("Verificação", client ? verificationStatusLabel(verificationStatus) : "Novo cadastro")}
            ${renderSummaryStat("Último fallback", client ? latestPnvText : "Ainda não solicitado")}
          </div>

          ${client ? `
            <div class="action-cluster">
              <div class="cluster-title">Ações rápidas</div>
              <div class="action-grid">
                <button type="button" class="toolbar-btn" data-action="add-credit">+1 crédito</button>
                <button type="button" class="toolbar-btn" data-action="remove-credit">-1 crédito</button>
                <button type="button" class="toolbar-btn" data-action="request-manual-pnv">Solicitar fallback manual</button>
              </div>
            </div>
            <div class="summary-footnote">
              <strong>Motivo técnico</strong>
              <span>${escapeHtml(verificationReason)}</span>
            </div>
          ` : ""}
        </aside>

        <section class="client-editor">
          <form id="client-profile-form" class="client-form">
            <div class="form-grid">
              <label class="field">
                <span>Nome</span>
                <input id="client-name" type="text" value="${escapeHtml(draft.name || "")}" placeholder="Nome do cliente" required>
              </label>

              <label class="field">
                <span>Telefone</span>
                <input id="client-phone" type="text" value="${escapeHtml(draft.phone || "")}" placeholder="+5565..." required>
              </label>

              <label class="field field-wide">
                <span>E-mail principal (opcional)</span>
                <input id="client-email" type="email" value="${escapeHtml(draft.email || "")}" placeholder="manual e com consentimento">
              </label>

              <label class="field field-wide notes-field">
                <span>Observações</span>
                <textarea id="client-notes" rows="5" placeholder="Contexto útil, histórico, validações e acordos com o cliente.">${escapeHtml(draft.notes || "")}</textarea>
              </label>
            </div>

            <div class="form-footer">
              <div class="form-guidance">
                <strong id="profile-form-status">${escapeHtml(primaryAction.helperTitle)}</strong>
                <p id="profile-form-hint">${escapeHtml(primaryAction.helperText)}</p>
              </div>
              <button id="client-save-btn" class="primary-btn ${primaryAction.disabled ? "" : "is-ready"}" type="submit" ${primaryAction.disabled ? "disabled" : ""}>
                ${escapeHtml(primaryAction.label)}
              </button>
            </div>
          </form>

          ${client ? `
            <div class="details-block ${verificationExpanded ? "is-open" : ""}">
              <button type="button" class="section-toggle" id="toggle-verification">
                <span>Verificação e segurança</span>
                <small>${escapeHtml(verificationExpanded ? "Ocultar" : "Expandir")}</small>
              </button>
              <div class="details-body">
                <div class="detail-grid">
                  <div>
                    <strong>Status atual</strong>
                    <span>${escapeHtml(verificationStatusLabel(verificationStatus))}</span>
                  </div>
                  <div>
                    <strong>Telefone verificado</strong>
                    <span>${escapeHtml(verification?.verifiedPhone || "Ainda não confirmado")}</span>
                  </div>
                  <div>
                    <strong>Última tentativa</strong>
                    <span>${escapeHtml(latestPnvText)}</span>
                  </div>
                  <div>
                    <strong>Motivo técnico</strong>
                    <span>${escapeHtml(verificationReason)}</span>
                  </div>
                </div>
                <div class="detail-actions">
                  <button type="button" class="toolbar-btn" data-action="mark-verified-manual">Confirmar manualmente</button>
                  <button type="button" class="toolbar-btn" data-action="mark-mismatch">Marcar divergência</button>
                </div>
              </div>
            </div>

            <div class="details-block is-open">
              <div class="section-heading">
                <div>
                  <h4>Atendimento</h4>
                  <p>${escapeHtml(activeSession ? "Finalize o chamado ativo quando a intervenção estiver concluída." : "Sem sessão ativa no momento.")}</p>
                </div>
                ${activeSession ? '<span class="status-pill info">Sessão ativa</span>' : ""}
              </div>
              <div class="detail-actions">
                <button type="button" class="toolbar-btn ${activeSession ? "accent" : ""}" data-action="close-session" ${activeSession ? "" : "disabled"}>
                  Registrar atendimento concluído
                </button>
              </div>
            </div>
          ` : ""}
        </section>
      </div>
    </div>
  `;

  bindProfileInteractions(client);
  syncProfileEditorState();
}
function bindProfileInteractions(client) {
  const form = profileContainer.querySelector("#client-profile-form");
  if (form) {
    const syncDraft = () => {
      state.clientDraft = readDraftFromForm(form);
      syncProfileEditorState();
    };

    form.addEventListener("input", syncDraft);
    form.addEventListener("change", syncDraft);
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const payload = getSanitizedDraft(state.clientDraft);
      if (!payload.name || !payload.phone) {
        alert("Nome e telefone são obrigatórios.");
        return;
      }

      try {
        const clientId = await registerOrUpdateClient(payload);
        state.profileNotice = {
          tone: "ok",
          text: client ? "Alterações salvas com sucesso." : "Cliente cadastrado com sucesso."
        };
        searchPhoneInput.value = payload.phone;
        await loadClientProfile(clientId);
        await loadQueue();
      } catch (err) {
        console.error(err);
        alert(client ? "Não foi possível salvar as alterações." : "Não foi possível cadastrar o cliente.");
      }
    });
  }

  const toggleVerificationBtn = profileContainer.querySelector("#toggle-verification");
  if (toggleVerificationBtn) {
    toggleVerificationBtn.addEventListener("click", () => {
      state.sections.verification = !state.sections.verification;
      renderProfileFromState();
    });
  }

  profileContainer.querySelectorAll("[data-action]").forEach((button) => {
    button.addEventListener("click", async () => {
      if (!client) return;
      const action = button.dataset.action;
      try {
        if (action === "add-credit") {
          await adjustCredits(client.id, 1);
          setProfileNotice("ok", "Crédito adicionado com sucesso.");
        } else if (action === "remove-credit") {
          await adjustCredits(client.id, -1);
          setProfileNotice("ok", "Crédito removido com sucesso.");
        } else if (action === "request-manual-pnv") {
          await requestManualVerification(client);
          setProfileNotice("ok", "Fallback manual solicitado.");
        } else if (action === "mark-verified-manual") {
          const manualPhone = normalizePhone(prompt("Informe o telefone confirmado manualmente:", client.phone || "") || "");
          if (!manualPhone) {
            alert("Telefone inválido.");
            return;
          }
          await confirmManualVerification(client, manualPhone);
          setProfileNotice("ok", "Verificação manual concluída com sucesso.");
        } else if (action === "mark-mismatch") {
          const reason = prompt("Motivo da divergência:", "phone_divergent_manual") || "phone_divergent_manual";
          await markVerificationMismatch(client, reason);
          setProfileNotice("warn", "Cliente marcado como divergente.");
        } else if (action === "close-session") {
          const problemSummary = prompt("Resumo do problema:");
          const solutionSummary = prompt("Solução aplicada:");
          await closeLatestOpenSession(client.id, {
            problemSummary,
            solutionSummary,
            internalNotes: "Fechado pelo painel técnico"
          });
          setProfileNotice("ok", "Atendimento encerrado no painel técnico.");
        }

        await loadClientProfile(client.id);
        await loadQueue();
      } catch (err) {
        console.error(err);
        alert("Não foi possível concluir a ação solicitada.");
      }
    });
  });
}

function syncProfileEditorState() {
  const saveBtn = profileContainer.querySelector("#client-save-btn");
  const statusNode = profileContainer.querySelector("#profile-form-status");
  const hintNode = profileContainer.querySelector("#profile-form-hint");
  const heroTitleNode = profileContainer.querySelector("#profile-hero-title");
  const summaryPhoneNode = profileContainer.querySelector("#summary-phone");
  const summaryEmailNode = profileContainer.querySelector("#summary-email");

  if (!saveBtn || !statusNode || !hintNode) return;

  const action = getPrimaryActionMeta();
  saveBtn.textContent = action.label;
  saveBtn.disabled = action.disabled;
  saveBtn.classList.toggle("is-ready", !action.disabled);
  statusNode.textContent = action.helperTitle;
  hintNode.textContent = action.helperText;

  if (heroTitleNode) {
    heroTitleNode.textContent = state.clientDraft?.name || (state.draftMode === "new" ? "Novo cadastro" : "Cliente sem nome");
  }

  if (summaryPhoneNode) {
    summaryPhoneNode.textContent = normalizePhone(state.clientDraft?.phone) || state.clientDraft?.phone || "Telefone não informado";
  }

  if (summaryEmailNode) {
    summaryEmailNode.textContent = state.clientDraft?.email || "Sem e-mail principal";
  }
}

function getPrimaryActionMeta() {
  const sanitizedDraft = getSanitizedDraft(state.clientDraft);
  const hasName = Boolean(sanitizedDraft.name);
  const hasPhone = Boolean(sanitizedDraft.phone);
  const isValid = hasName && hasPhone;
  const isDirty = isProfileDirty();

  if (state.draftMode === "new") {
    return {
      label: "Cadastrar cliente",
      disabled: !isValid,
      helperTitle: "Cadastro novo",
      helperText: isValid
        ? "Tudo pronto para criar a ficha com os dados principais."
        : "Preencha nome e telefone para habilitar o cadastro."
    };
  }

  return {
    label: isDirty ? "Salvar alterações" : "Sem alterações",
    disabled: !isValid || !isDirty,
    helperTitle: isDirty ? "Alterações prontas para salvar" : "Ficha sincronizada",
    helperText: !isValid
      ? "Corrija os campos obrigatórios para liberar o salvamento."
      : isDirty
        ? "Revise os campos editados e confirme para atualizar a ficha."
        : "Edite nome, telefone, e-mail ou observações para ativar o salvamento."
  };
}

function isProfileDirty() {
  if (!state.clientDraft || !state.clientOriginal) return false;

  const current = getSanitizedDraft(state.clientDraft);
  const original = getSanitizedDraft(state.clientOriginal);
  return current.name !== original.name
    || current.phone !== original.phone
    || current.email !== original.email
    || current.notes !== original.notes;
}

function readDraftFromForm(form) {
  return {
    name: form.querySelector("#client-name")?.value || "",
    phone: form.querySelector("#client-phone")?.value || "",
    email: form.querySelector("#client-email")?.value || "",
    notes: form.querySelector("#client-notes")?.value || ""
  };
}

function getSanitizedDraft(draft) {
  return {
    name: String(draft?.name || "").trim(),
    phone: normalizePhone(draft?.phone) || "",
    email: String(draft?.email || "").trim(),
    notes: String(draft?.notes || "").trim()
  };
}

function buildDraftFromClient(client) {
  return {
    name: client?.name || "",
    phone: client?.phone || "",
    email: client?.primaryEmail || "",
    notes: client?.notes || ""
  };
}

function renderProfileNotice() {
  if (!state.profileNotice) return "";
  return `
    <div class="inline-notice ${escapeHtml(state.profileNotice.tone || "info")}">
      ${escapeHtml(state.profileNotice.text || "")}
    </div>
  `;
}

function setProfileNotice(tone, text) {
  state.profileNotice = { tone, text };
}

function renderSummaryStat(label, value) {
  return `
    <div class="summary-stat">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(String(value))}</strong>
    </div>
  `;
}
async function loadHistory(clientId) {
  historyList.innerHTML = "<small>Carregando histórico...</small>";
  const sessionsSnap = await getDocs(query(collection(db, COLLECTIONS.supportSessions)));
  const reportsSnap = await getDocs(query(collection(db, COLLECTIONS.supportReports)));

  const sessions = sessionsSnap.docs
    .map((item) => ({ id: item.id, ...item.data() }))
    .filter((session) => session.clientId === clientId)
    .sort((a, b) => (b.startedAt ?? 0) - (a.startedAt ?? 0));

  const reportsBySession = new Map(
    reportsSnap.docs.map((item) => {
      const data = item.data();
      return [data.sessionId, { id: item.id, ...data }];
    })
  );

  if (!sessions.length) {
    historyList.innerHTML = "<small>Sem histórico de atendimento.</small>";
    return;
  }

  historyList.innerHTML = "";
  sessions.forEach((session) => {
    const report = reportsBySession.get(session.id);
    const item = document.createElement("article");
    item.className = "history-item";
    item.innerHTML = `
      <div class="history-head">
        <strong>${escapeHtml(formatDate(session.startedAt))}</strong>
        <span class="badge ${session.status === "completed" ? "ok" : "warn"}">${escapeHtml(sessionStatusLabel(session.status))}</span>
      </div>
      <div>Técnico: ${escapeHtml(session.techName || session.techId || "-")}</div>
      <div>Problema: ${escapeHtml(session.problemSummary || "-")}</div>
      <div>Solução: ${escapeHtml(session.solutionSummary || report?.solutionApplied || "-")}</div>
      <div>Observações: ${escapeHtml(session.internalNotes || report?.actionsTaken || "-")}</div>
      <div>Gratuito: ${session.isFreeFirstSupport ? "Sim" : "Não"} | Créditos consumidos: ${escapeHtml(String(session.creditsConsumed ?? 0))}</div>
    `;
    historyList.appendChild(item);
  });
}

async function loadOrders(clientId = null) {
  ordersList.innerHTML = "<small>Carregando pedidos...</small>";
  const snap = await getDocs(collection(db, COLLECTIONS.creditOrders));
  const rows = snap.docs
    .map((item) => ({ id: item.id, ...item.data() }))
    .filter((order) => (clientId ? order.clientId === clientId : true))
    .sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0))
    .slice(0, 40);

  if (!rows.length) {
    ordersList.innerHTML = `<small>${clientId ? "Sem pedidos de crédito para este cliente." : "Sem pedidos de crédito."}</small>`;
    return;
  }

  ordersList.innerHTML = "";
  rows.forEach((order) => {
    const pkg = state.packages.find((item) => item.id === order.packageId);
    const item = document.createElement("article");
    item.className = "order-item";
    item.innerHTML = `
      <div class="history-head">
        <strong>${escapeHtml(pkg?.name || order.packageId || "-")}</strong>
        <span class="badge ${order.status === "approved" ? "ok" : "warn"}">${escapeHtml(order.status || "-")}</span>
      </div>
      <div>Cliente: ${escapeHtml(order.clientId || "-")}</div>
      <div>Método: ${escapeHtml(order.paymentMethod || "-")}</div>
      <div>Valor: ${escapeHtml(formatMoney(order.amountCents || 0))}</div>
      <div>Solicitado via WhatsApp: ${order.whatsappRequested ? "Sim" : "Não"}</div>
    `;
    ordersList.appendChild(item);
  });
}

async function getLatestPnvRequestForClient(clientId) {
  const snap = await getDocs(collection(db, COLLECTIONS.pnvRequests));
  return snap.docs
    .map((item) => ({ id: item.id, ...item.data() }))
    .filter((request) => request.clientId === clientId)
    .sort((a, b) => (b.updatedAt ?? b.createdAt ?? 0) - (a.updatedAt ?? a.createdAt ?? 0))[0] || null;
}

async function getLatestOpenSessionForClient(clientId) {
  const snap = await getDocs(collection(db, COLLECTIONS.supportSessions));
  return snap.docs
    .map((item) => ({ id: item.id, ...item.data() }))
    .filter((row) => row.clientId === clientId && (row.status === "in_progress" || row.status === "queued"))
    .sort((a, b) => (b.startedAt ?? 0) - (a.startedAt ?? 0))[0] || null;
}

async function resolveClientUidByClientId(clientId) {
  const linksSnap = await getDocs(collection(db, COLLECTIONS.clientAppLinks));
  const link = linksSnap.docs
    .map((item) => item.data())
    .find((row) => row.clientId === clientId);
  return link?.clientUid || null;
}

async function resolveClientIdByPhone(phone) {
  const normalizedPhone = normalizePhone(phone);
  if (!normalizedPhone) return null;
  const candidateIds = new Set();
  const directId = clientDocIdFromPhone(normalizedPhone);
  const directSnap = await getDoc(doc(db, COLLECTIONS.clients, directId));
  if (directSnap.exists()) {
    candidateIds.add(directSnap.id);
  }

  const verifiedMatches = await getDocs(
    query(collection(db, COLLECTIONS.clientVerifications), where("verifiedPhone", "==", normalizedPhone))
  );
  verifiedMatches.docs.forEach((item) => {
    const clientId = item.data().clientId;
    if (clientId) candidateIds.add(clientId);
  });

  const primaryMatches = await getDocs(
    query(collection(db, COLLECTIONS.clientVerifications), where("primaryPhone", "==", normalizedPhone))
  );
  primaryMatches.docs.forEach((item) => {
    const clientId = item.data().clientId;
    if (clientId) candidateIds.add(clientId);
  });

  let bestClient = null;
  let bestScore = Number.NEGATIVE_INFINITY;
  for (const candidateId of candidateIds) {
    const clientSnap = await getDoc(doc(db, COLLECTIONS.clients, candidateId));
    if (!clientSnap.exists()) continue;
    const client = { id: clientSnap.id, ...clientSnap.data() };
    const score = (client.freeFirstSupportUsed ? 1000 : 0)
      + (Number(client.supportsUsed ?? 0) * 100)
      + (client.profileCompleted ? 10 : 0)
      + Math.floor(Number(client.updatedAt ?? 0) / 1000);
    if (score > bestScore) {
      bestScore = score;
      bestClient = client;
    }
  }

  return bestClient?.id || null;
}
async function requestManualVerification(client) {
  const now = Date.now();
  const pnvRequestExpiresAt = expiresAtFromMillis(now, RETENTION_DAYS.pnvRequests);
  const clientUid = await resolveClientUidByClientId(client.id);
  await addDoc(collection(db, COLLECTIONS.pnvRequests), {
    clientUid,
    clientId: client.id,
    phone: client.phone || null,
    status: "manual_pending",
    manualFallback: true,
    reason: "manual_requested_by_technician",
    source: "tech_panel",
    createdAt: now,
    updatedAt: now,
    expiresAt: pnvRequestExpiresAt
  });
  await setDoc(doc(db, COLLECTIONS.clientVerifications, client.id), {
    clientId: client.id,
    primaryPhone: client.phone || null,
    verifiedPhone: null,
    status: "manual_required",
    mismatchReason: "manual_requested_by_technician",
    lastVerificationAt: now,
    updatedAt: now
  }, { merge: true });
}

async function confirmManualVerification(client, verifiedPhone) {
  const now = Date.now();
  const pnvRequestExpiresAt = expiresAtFromMillis(now, RETENTION_DAYS.pnvRequests);
  const clientUid = await resolveClientUidByClientId(client.id);
  await setDoc(doc(db, COLLECTIONS.clients, client.id), {
    phone: verifiedPhone,
    updatedAt: now
  }, { merge: true });
  await setDoc(doc(db, COLLECTIONS.clientVerifications, client.id), {
    clientId: client.id,
    primaryPhone: verifiedPhone,
    verifiedPhone,
    status: "verified",
    mismatchReason: null,
    lastVerificationAt: now,
    updatedAt: now
  }, { merge: true });
  await addDoc(collection(db, COLLECTIONS.pnvRequests), {
    clientUid,
    clientId: client.id,
    phone: verifiedPhone,
    status: "processed",
    manualFallback: true,
    reason: "manual_verified_by_technician",
    source: "tech_panel",
    createdAt: now,
    updatedAt: now,
    expiresAt: pnvRequestExpiresAt
  });
}

async function markVerificationMismatch(client, reason) {
  const now = Date.now();
  const pnvRequestExpiresAt = expiresAtFromMillis(now, RETENTION_DAYS.pnvRequests);
  const clientUid = await resolveClientUidByClientId(client.id);
  await setDoc(doc(db, COLLECTIONS.clientVerifications, client.id), {
    clientId: client.id,
    primaryPhone: client.phone || null,
    verifiedPhone: null,
    status: "mismatch",
    mismatchReason: reason,
    lastVerificationAt: now,
    updatedAt: now
  }, { merge: true });
  await addDoc(collection(db, COLLECTIONS.pnvRequests), {
    clientUid,
    clientId: client.id,
    phone: client.phone || null,
    status: "manual_pending",
    manualFallback: true,
    reason: reason || "phone_divergent_manual",
    source: "tech_panel",
    createdAt: now,
    updatedAt: now,
    expiresAt: pnvRequestExpiresAt
  });
}

async function registerOrUpdateClient(payload) {
  const now = Date.now();
  const clientId = await resolveClientIdByPhone(payload.phone) || clientDocIdFromPhone(payload.phone);
  const clientRef = doc(db, COLLECTIONS.clients, clientId);
  const profileRef = doc(db, COLLECTIONS.clientProfiles, clientId);

  await runTransaction(db, async (tx) => {
    const snap = await tx.get(clientRef);
    const oldData = snap.exists() ? snap.data() : {};
    const credits = Number(oldData.credits ?? 0);
    const supportsUsed = Number(oldData.supportsUsed ?? 0);
    const freeUsed = Boolean(oldData.freeFirstSupportUsed ?? false);
    tx.set(clientRef, {
      phone: payload.phone,
      name: payload.name,
      primaryEmail: payload.email || oldData.primaryEmail || null,
      notes: payload.notes || oldData.notes || null,
      credits,
      supportsUsed,
      freeFirstSupportUsed: freeUsed,
      status: deriveClientStatus({ credits, freeFirstSupportUsed: freeUsed }),
      createdAt: oldData.createdAt || now,
      updatedAt: now
    }, { merge: true });

    if (!snap.exists()) {
      tx.set(profileRef, {
        clientId,
        totalSessions: 0,
        totalPaidSessions: 0,
        totalFreeSessions: 0,
        totalCreditsPurchased: 0,
        totalCreditsUsed: 0,
        lastSupportAt: null,
        createdAt: now,
        updatedAt: now
      }, { merge: true });
    }
  });
  return clientId;
}

async function appendClientNotes(clientId, note) {
  const clientRef = doc(db, COLLECTIONS.clients, clientId);
  const snapshot = await getDoc(clientRef);
  if (!snapshot.exists()) return;
  const old = snapshot.data().notes || "";
  const merged = old ? `${old}\n- ${note}` : `- ${note}`;
  await updateDoc(clientRef, {
    notes: merged,
    updatedAt: Date.now()
  });
}

async function adjustCredits(clientId, delta) {
  await runTransaction(db, async (tx) => {
    const clientRef = doc(db, COLLECTIONS.clients, clientId);
    const snap = await tx.get(clientRef);
    if (!snap.exists()) return;
    const current = snap.data();
    const credits = Number(current.credits ?? 0);
    const freeFirstSupportUsed = Boolean(current.freeFirstSupportUsed ?? false);
    const nextCredits = Math.max(0, credits + delta);
    tx.set(clientRef, {
      credits: nextCredits,
      status: deriveClientStatus({
        credits: nextCredits,
        freeFirstSupportUsed
      }),
      updatedAt: Date.now()
    }, { merge: true });
  });
}

async function closeLatestOpenSession(clientId, payload) {
  const sessionsSnap = await getDocs(collection(db, COLLECTIONS.supportSessions));
  const latest = sessionsSnap.docs
    .map((item) => ({ id: item.id, ...item.data() }))
    .filter((row) => row.clientId === clientId && (row.status === "in_progress" || row.status === "queued"))
    .sort((a, b) => (b.startedAt ?? 0) - (a.startedAt ?? 0))[0];

  if (!latest) {
    alert("Nenhuma sessão aberta encontrada para este cliente.");
    return;
  }

  const now = Date.now();
  const supportSessionExpiresAt = expiresAtFromMillis(now, RETENTION_DAYS.supportSessions);
  const sessionRef = doc(db, COLLECTIONS.supportSessions, latest.id);
  const clientRef = doc(db, COLLECTIONS.clients, clientId);
  const profileRef = doc(db, COLLECTIONS.clientProfiles, clientId);

  await runTransaction(db, async (tx) => {
    const sessionSnap = await tx.get(sessionRef);
    const clientSnap = await tx.get(clientRef);
    const profileSnap = await tx.get(profileRef);
    if (!sessionSnap.exists() || !clientSnap.exists()) return;

    const session = sessionSnap.data();
    const client = clientSnap.data();
    const profile = profileSnap.exists() ? profileSnap.data() : {};
    const alreadyApplied = Boolean(session.billingAppliedAt);

    if (!alreadyApplied) {
      const isFree = Boolean(session.isFreeFirstSupport);
      const creditsConsumed = Number(session.creditsConsumed ?? (isFree ? 0 : 1));
      const oldCredits = Number(client.credits ?? 0);
      const oldSupportsUsed = Number(client.supportsUsed ?? 0);
      const freeUsed = Boolean(client.freeFirstSupportUsed ?? false);
      const nextCredits = isFree ? oldCredits : Math.max(0, oldCredits - creditsConsumed);
      const freeUsedAfter = freeUsed || isFree;

      tx.set(clientRef, {
        credits: nextCredits,
        supportsUsed: oldSupportsUsed + 1,
        freeFirstSupportUsed: freeUsedAfter,
        status: deriveClientStatus({
          credits: nextCredits,
          freeFirstSupportUsed: freeUsedAfter
        }),
        updatedAt: now
      }, { merge: true });

      tx.set(profileRef, {
        clientId,
        totalSessions: Number(profile.totalSessions ?? 0) + 1,
        totalPaidSessions: Number(profile.totalPaidSessions ?? 0) + (isFree ? 0 : 1),
        totalFreeSessions: Number(profile.totalFreeSessions ?? 0) + (isFree ? 1 : 0),
        totalCreditsPurchased: Number(profile.totalCreditsPurchased ?? 0),
        totalCreditsUsed: Number(profile.totalCreditsUsed ?? 0) + creditsConsumed,
        lastSupportAt: now,
        updatedAt: now
      }, { merge: true });
    }

    tx.set(sessionRef, {
      status: "completed",
      endedAt: now,
      problemSummary: payload.problemSummary || session.problemSummary || null,
      solutionSummary: payload.solutionSummary || session.solutionSummary || null,
      internalNotes: payload.internalNotes || session.internalNotes || null,
      billingAppliedAt: session.billingAppliedAt || now,
      expiresAt: supportSessionExpiresAt,
      updatedAt: now
    }, { merge: true });
  });

  const supportReportExpiresAt = expiresAtFromMillis(now, RETENTION_DAYS.supportReports);
  await addDoc(collection(db, COLLECTIONS.supportReports), {
    sessionId: latest.id,
    clientId,
    techId: null,
    createdAt: now,
    expiresAt: supportReportExpiresAt,
    summary: payload.problemSummary || null,
    actionsTaken: payload.internalNotes || null,
    solutionApplied: payload.solutionSummary || null,
    followUpNeeded: false
  });
}
function expiresAtFromMillis(baseMillis, days) {
  const safeDays = Number.isFinite(days) ? Math.max(0, days) : 0;
  return Timestamp.fromMillis(baseMillis + safeDays * 24 * 60 * 60 * 1000);
}

function deriveClientStatus(client) {
  if (!client.freeFirstSupportUsed) return "first_support_pending";
  if ((client.credits ?? 0) > 0) return "with_credit";
  return "without_credit";
}

function verificationStatusLabel(status) {
  switch (status) {
    case "verified":
      return "Verificado";
    case "manual_required":
      return "Ação manual necessária";
    case "mismatch":
      return "Divergente";
    case "pending":
    default:
      return "Pendente";
  }
}

function pnvStatusLabel(status) {
  switch (status) {
    case "processed":
      return "Tratado manualmente";
    case "manual_pending":
      return "Aguardando ação manual";
    case "pending":
    default:
      return "Pendente";
  }
}

function sessionStatusLabel(status) {
  switch (status) {
    case "completed":
      return "Concluída";
    case "in_progress":
      return "Em andamento";
    case "queued":
      return "Na fila";
    default:
      return status || "-";
  }
}

function verificationBadgeClass(status) {
  switch (status) {
    case "verified":
      return "ok";
    case "manual_required":
      return "danger";
    case "mismatch":
      return "danger";
    case "pending":
    default:
      return "warn";
  }
}

function financialStatusTone(client) {
  if (!client.freeFirstSupportUsed) return "info";
  if ((client.credits ?? 0) > 0) return "ok";
  return "warn";
}

function normalizePhone(value) {
  const raw = String(value || "").trim();
  if (!raw) return null;
  const digits = raw.replace(/[^\d+]/g, "");
  const justDigits = digits.replace(/\D/g, "");
  if (justDigits.length < 10) return null;
  return digits.startsWith("+") ? digits : `+${justDigits}`;
}

function clientDocIdFromPhone(phone) {
  return `phone_${phone.replace(/\D/g, "")}`;
}

function formatDate(ts) {
  if (!ts) return "-";
  const millis = typeof ts?.toMillis === "function" ? ts.toMillis() : ts;
  return new Date(millis).toLocaleString("pt-BR");
}

function formatMoney(cents) {
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL"
  }).format((Number(cents) || 0) / 100);
}

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

void appendClientNotes;
