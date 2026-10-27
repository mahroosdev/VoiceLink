(() => {
  const page = document.getElementById("room-page");
  const form = document.getElementById("glossary-form");
  if (!page || !form) return;

  const roomId = page.dataset.roomId;
  const endpoint = "/api/rooms/" + roomId + "/glossary";
  const panel = document.getElementById("glossary-section");
  const trigger = document.getElementById("glossary-open");
  const close = document.getElementById("glossary-close");
  const view = document.getElementById("glossary-view");
  const empty = document.getElementById("glossary-empty");
  const add = document.getElementById("glossary-add");
  const list = document.getElementById("glossary-list");
  const status = document.getElementById("glossary-status");
  const capacity = document.getElementById("glossary-capacity");
  const heading = document.getElementById("glossary-form-heading");
  const save = document.getElementById("glossary-save");
  const cancel = document.getElementById("glossary-cancel");
  const sourceLanguage = document.getElementById("glossary-source-language");
  const targetLanguage = document.getElementById("glossary-target-language");
  const sourceTerm = document.getElementById("glossary-source-term");
  const preferredTerm = document.getElementById("glossary-preferred-term");
  const editable = form.dataset.canEdit === "true";
  let editing = null;
  let count = 0;
  let renderedSignature = null;

  const roomClosed = () => document.getElementById("room-status").textContent === "CLOSED";
  const languageName = tag => tag === "ta" ? "Tamil" : "English";

  function clearValidation() {
    sourceTerm.removeAttribute("aria-invalid");
    preferredTerm.removeAttribute("aria-invalid");
  }

  function setEditable(enabled) {
    for (const control of form.elements) control.disabled = !enabled;
  }

  function clearEdit() {
    editing = null;
    form.reset();
    heading.textContent = "Add term";
    save.textContent = "Add term";
    clearValidation();
    form.hidden = true;
    view.hidden = false;
    setEditable(editable && !roomClosed() && count < 12);
  }

  function showForm() {
    view.hidden = true;
    form.hidden = false;
    status.textContent = "";
    delete status.dataset.kind;
    (editing ? sourceTerm : sourceLanguage).focus();
  }

  function errorMessage(response, error) {
    if (error === "Glossary entry changed; refresh and retry")
      return "This term changed in another tab. Refresh and try again.";
    if (error === "Room glossary is full")
      return "The room glossary is full. Edit or delete a term.";
    if (error === "Glossary term already exists")
      return "This term already exists for that language direction.";
    if (error === "Invalid glossary term")
      return "Check term lengths and remove newlines or control characters.";
    if (error === "Room invite has expired")
      return "The invite expired. Close this room and create a new one.";
    if (response.status === 404 || response.status === 403)
      return "Room terms are no longer available.";
    return "The term could not be saved. Please try again.";
  }

  async function readError(response) {
    const body = await response.json().catch(() => ({}));
    return errorMessage(response, body.error);
  }

  function render(entries) {
    const nextSignature = JSON.stringify(entries.map(entry => [entry.id, entry.rowVersion,
      entry.sourceLanguage, entry.targetLanguage, entry.sourceTerm, entry.preferredTerm]));
    const changed = renderedSignature !== null && renderedSignature !== nextSignature;
    renderedSignature = nextSignature;
    list.replaceChildren();
    count = entries.length;
    capacity.textContent = count + "/12 terms";
    empty.hidden = count !== 0;
    add.hidden = !editable || roomClosed();
    add.disabled = count >= 12;
    for (const entry of entries) {
      const row = document.createElement("li");
      row.className = "glossary-item";
      const direction = document.createElement("span");
      direction.className = "glossary-item__direction";
      direction.textContent = languageName(entry.sourceLanguage) + " → " +
        languageName(entry.targetLanguage);
      const terms = document.createElement("span");
      terms.className = "glossary-item__terms";
      const original = document.createElement("strong");
      original.textContent = entry.sourceTerm;
      original.lang = entry.sourceLanguage;
      const arrow = document.createTextNode(" → ");
      const preferred = document.createElement("span");
      preferred.textContent = entry.preferredTerm;
      preferred.lang = entry.preferredTerm === entry.sourceTerm
        ? entry.sourceLanguage : entry.targetLanguage;
      terms.append(original, arrow, preferred);
      row.append(direction, terms);

      if (editable && !roomClosed()) {
        const actions = document.createElement("div");
        actions.className = "glossary-item__actions";
        const edit = document.createElement("button");
        edit.type = "button";
        edit.textContent = "Edit";
        edit.setAttribute("aria-label", "Edit term " + entry.sourceTerm);
        edit.addEventListener("click", () => {
          editing = entry;
          sourceLanguage.value = entry.sourceLanguage;
          targetLanguage.value = entry.targetLanguage;
          sourceTerm.value = entry.sourceTerm;
          preferredTerm.value = entry.preferredTerm === entry.sourceTerm
            ? "" : entry.preferredTerm;
          heading.textContent = "Edit term";
          save.textContent = "Save changes";
          cancel.hidden = false;
          clearValidation();
          setEditable(true);
          showForm();
        });
        const remove = document.createElement("button");
        remove.type = "button";
        remove.textContent = "Delete";
        remove.setAttribute("aria-label", "Delete term " + entry.sourceTerm);
        remove.addEventListener("click", async () => {
          if (!window.confirm("Delete this room term?")) return;
          remove.disabled = true;
          try {
            const response = await send("DELETE", endpoint + "/" + entry.id,
              { expectedVersion: entry.rowVersion });
            if (!response.ok) throw new Error(await readError(response));
            status.textContent = "Term deleted.";
            clearEdit();
            await refresh();
            (add.disabled || add.hidden ? document.getElementById("glossary-refresh") : add).focus();
          } catch (error) {
            status.textContent = error.message;
            status.dataset.kind = "error";
            remove.disabled = false;
          }
        });
        actions.append(edit, remove);
        row.append(actions);
      }
      list.append(row);
    }
    setEditable(editable && !roomClosed() && (editing !== null || count < 12));
    if (editable && count === 12 && editing === null)
      status.textContent = "The room glossary is full. Edit or delete a term.";
    if (changed) window.dispatchEvent(new Event("voicelink-glossary-changed"));
  }

  async function send(method, url, body) {
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    return fetch(url, { method, credentials: "same-origin", headers: {
      "Content-Type": "application/json", [csrfHeader]: csrfToken
    }, body: JSON.stringify(body) });
  }

  async function refresh() {
    if (roomClosed()) return;
    try {
      const response = await fetch(endpoint, {
        credentials: "same-origin", headers: { Accept: "application/json" }
      });
      if (roomClosed()) return;
      if (!response.ok) throw new Error("Room terms are unavailable. Try refreshing.");
      const entries = await response.json();
      if (!roomClosed()) render(entries);
    } catch (error) {
      if (!roomClosed()) {
        status.textContent = error.message;
        status.dataset.kind = "error";
      }
    }
  }

  form.addEventListener("submit", async event => {
    event.preventDefault();
    if (!editable || roomClosed() || (count >= 12 && !editing)) return;
    clearValidation();
    delete status.dataset.kind;
    const body = { sourceLanguage: sourceLanguage.value,
      targetLanguage: targetLanguage.value, sourceTerm: sourceTerm.value,
      preferredTerm: preferredTerm.value };
    if (editing) body.expectedVersion = editing.rowVersion;
    save.disabled = true;
    try {
      const response = await send(editing ? "PUT" : "POST",
        editing ? endpoint + "/" + editing.id : endpoint, body);
      if (!response.ok) {
        if (response.status === 400) {
          sourceTerm.setAttribute("aria-invalid", "true");
          preferredTerm.setAttribute("aria-invalid", "true");
          sourceTerm.focus();
        }
        if (response.status === 409) {
          const message = await readError(response);
          if (message.startsWith("This term changed")) {
            clearEdit();
            await refresh();
          }
          throw new Error(message);
        }
        throw new Error(await readError(response));
      }
      status.textContent = editing ? "Term updated." : "Term added.";
      delete status.dataset.kind;
      clearEdit();
      await refresh();
      (add.disabled || add.hidden ? document.getElementById("glossary-refresh") : add).focus();
    } catch (error) {
      status.textContent = error.message;
      status.dataset.kind = "error";
    } finally {
      setEditable(editable && !roomClosed() && (editing !== null || count < 12));
    }
  });

  trigger.addEventListener("click", () => {
    if (roomClosed()) return;
    const details = document.getElementById("room-details");
    if (details?.open) details.close();
    panel.showModal();
    close.focus();
    refresh();
  });
  close.addEventListener("click", () => panel.close());
  panel.addEventListener("close", () => {
    clearEdit();
    status.textContent = "";
    delete status.dataset.kind;
    if (!roomClosed()) trigger.focus();
  });
  add.addEventListener("click", () => {
    if (!editable || roomClosed() || count >= 12) return;
    clearEdit();
    showForm();
  });
  cancel.addEventListener("click", () => {
    clearEdit();
    status.textContent = "";
    delete status.dataset.kind;
    (add.disabled || add.hidden ? document.getElementById("glossary-refresh") : add).focus();
  });
  sourceLanguage.addEventListener("change", () => {
    targetLanguage.value = sourceLanguage.value === "en" ? "ta" : "en";
  });
  document.getElementById("glossary-refresh").addEventListener("click", refresh);
  window.addEventListener("voicelink-room-state", () => {
    if (roomClosed()) {
      if (panel.open) panel.close();
      panel.hidden = true;
      list.replaceChildren();
      renderedSignature = null;
      capacity.textContent = "";
      clearEdit();
      form.hidden = true;
      setEditable(false);
      status.textContent = "Room closed; saved terms were deleted.";
      delete status.dataset.kind;
    } else {
      panel.hidden = false;
      refresh();
    }
  });
  window.addEventListener("focus", refresh);

  setEditable(editable);
  refresh();
})();
