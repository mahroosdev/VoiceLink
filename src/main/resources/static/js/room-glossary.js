(() => {
  const page = document.getElementById("room-page");
  const form = document.getElementById("glossary-form");
  if (!page || !form) return;
  const roomId = page.dataset.roomId;
  const endpoint = `/api/rooms/${roomId}/glossary`;
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
  const roomClosed = () => document.getElementById("room-status").textContent === "CLOSED";

  function clearEdit() {
    editing = null;
    form.reset();
    heading.textContent = "Add term";
    save.textContent = "Add term";
    cancel.hidden = true;
  }

  function setEditable(enabled) {
    for (const control of form.elements) control.disabled = !enabled;
  }

  function render(entries) {
    list.replaceChildren();
    capacity.textContent = `${entries.length} of 12 room terms used`;
    for (const entry of entries) {
      const row = document.createElement("li");
      const description = document.createElement("span");
      description.textContent = `${entry.sourceLanguage} → ${entry.targetLanguage}: ${entry.sourceTerm} → ${entry.preferredTerm} `;
      row.append(description);
      if (editable && !roomClosed()) {
        const edit = document.createElement("button");
        edit.type = "button";
        edit.textContent = "Edit";
        edit.setAttribute("aria-label", `Edit term ${entry.sourceTerm}`);
        edit.addEventListener("click", () => {
          editing = entry;
          sourceLanguage.value = entry.sourceLanguage;
          targetLanguage.value = entry.targetLanguage;
          sourceTerm.value = entry.sourceTerm;
          preferredTerm.value = entry.preferredTerm;
          heading.textContent = "Edit term";
          save.textContent = "Save changes";
          cancel.hidden = false;
          setEditable(true);
          sourceTerm.focus();
        });
        const remove = document.createElement("button");
        remove.type = "button";
        remove.textContent = "Delete";
        remove.setAttribute("aria-label", `Delete term ${entry.sourceTerm}`);
        remove.addEventListener("click", async () => {
          if (!confirm("Delete this room term?")) return;
          try {
            const response = await send("DELETE", `${endpoint}/${entry.id}`,
              { expectedVersion: entry.rowVersion });
            if (!response.ok) throw new Error(`Delete failed (${response.status}). Refresh and retry.`);
            status.textContent = "Term deleted";
            clearEdit();
            await refresh();
          } catch (error) { status.textContent = error.message; }
        });
        row.append(edit, remove);
      }
      list.append(row);
    }
    setEditable(editable && !roomClosed()
      && (editing !== null || entries.length < 12));
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
      const response = await fetch(endpoint, { credentials: "same-origin", headers: { Accept: "application/json" } });
      if (roomClosed()) return;
      if (!response.ok) throw new Error(`Terms unavailable (${response.status}).`);
      const entries = await response.json();
      if (!roomClosed()) render(entries);
    } catch (error) { if (!roomClosed()) status.textContent = error.message; }
  }

  form.addEventListener("submit", async event => {
    event.preventDefault();
    if (!editable || roomClosed()) return;
    const body = { sourceLanguage: sourceLanguage.value, targetLanguage: targetLanguage.value,
      sourceTerm: sourceTerm.value, preferredTerm: preferredTerm.value };
    if (editing) body.expectedVersion = editing.rowVersion;
    try {
      const response = await send(editing ? "PUT" : "POST",
        editing ? `${endpoint}/${editing.id}` : endpoint, body);
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        const message = error.error === "Invalid glossary term"
          ? "Check the term lengths and remove newlines or control characters."
          : error.error || `Term could not be saved (${response.status}).`;
        throw new Error(message);
      }
      status.textContent = editing ? "Term updated" : "Term added";
      clearEdit();
      await refresh();
    } catch (error) { status.textContent = error.message; }
  });
  cancel.addEventListener("click", clearEdit);
  sourceLanguage.addEventListener("change", () => {
    targetLanguage.value = sourceLanguage.value === "en" ? "ta" : "en";
  });
  document.getElementById("glossary-refresh").addEventListener("click", refresh);
  window.addEventListener("voicelink-room-state", () => {
    if (roomClosed()) {
      list.replaceChildren();
      capacity.textContent = "";
      clearEdit();
      form.hidden = true;
      setEditable(false);
      status.textContent = "Room closed; saved terms were deleted.";
    } else refresh();
  });
  window.addEventListener("focus", refresh);
  setEditable(editable);
  refresh();
})();
