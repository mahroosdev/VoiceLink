(() => {
  "use strict";
  const list = document.getElementById("conversation-events");
  const empty = document.getElementById("turn-empty");
  const draft = document.getElementById("shared-text-draft");
  const latest = document.getElementById("history-latest");
  const buttons = {
    all: document.getElementById("history-all"),
    translated: document.getElementById("history-translated"),
    quick: document.getElementById("history-quick")
  };
  if (!list || Object.values(buttons).some(button => !button)) return;
  let filter = "all";

  function nearBottom() {
    return list.scrollHeight - list.scrollTop - list.clientHeight <= 80;
  }

  function applyFilter() {
    let visible = 0;
    for (const row of list.children) {
      const kind = row.dataset.messageKind || "translated";
      row.hidden = filter !== "all" && kind !== filter;
      if (!row.hidden) visible++;
    }
    draft?.classList.toggle("history-filtered", filter === "quick");
    const showingDraft = draft && !draft.hidden && filter !== "quick";
    if (empty) {
      empty.hidden = visible > 0 || !!showingDraft;
      empty.textContent = filter === "quick" ? "No quick chat messages yet."
        : filter === "translated" ? "No translated messages yet."
          : "No conversation activity yet.";
    }
    if (nearBottom()) latest.hidden = true;
  }

  for (const [name, button] of Object.entries(buttons)) {
    button.addEventListener("click", () => {
      filter = name;
      for (const [key, item] of Object.entries(buttons))
        item.setAttribute("aria-pressed", String(key === name));
      latest.hidden = true;
      applyFilter();
    });
  }
  latest.addEventListener("click", () => {
    const reducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
    list.scrollTo({ top: list.scrollHeight, behavior: reducedMotion ? "auto" : "smooth" });
    latest.hidden = true;
  });
  list.addEventListener("scroll", () => {
    if (nearBottom()) latest.hidden = true;
  }, { passive: true });

  window.voicelinkHistory = {
    beforeAppend: nearBottom,
    changed(wasNearBottom, kind) {
      applyFilter();
      if (filter !== "all" && kind !== filter) return;
      if (wasNearBottom) {
        list.scrollTop = list.scrollHeight;
        latest.hidden = true;
      } else {
        latest.hidden = false;
      }
    },
    refresh: applyFilter
  };
  applyFilter();
})();
