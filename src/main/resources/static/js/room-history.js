(() => {
  "use strict";
  const list = document.getElementById("conversation-events");
  const empty = document.getElementById("turn-empty");
  const latest = document.getElementById("history-latest");
  const filterStatus = document.getElementById("history-filter-status");
  const buttons = {
    translated: document.getElementById("history-translated"),
    quick: document.getElementById("history-quick")
  };
  if (!list || Object.values(buttons).some(button => !button)) return;
  let filter = "translated";

  function nearBottom() {
    return list.scrollHeight - list.scrollTop - list.clientHeight <= 80;
  }

  function applyFilter() {
    let visible = 0;
    for (const row of list.children) {
      const kind = row.dataset.messageKind;
      row.hidden = kind !== filter;
      if (!row.hidden) visible++;
    }
    if (empty) {
      empty.hidden = visible > 0;
      empty.textContent = filter === "quick" ? "No quick chat messages yet."
        : "No translated messages yet.";
    }
    if (nearBottom()) latest.hidden = true;
  }

  for (const [name, button] of Object.entries(buttons)) {
    button.addEventListener("click", () => {
      filter = name;
      for (const [key, item] of Object.entries(buttons))
        item.setAttribute("aria-pressed", String(key === name));
      if (filterStatus) filterStatus.textContent = button.textContent + " selected.";
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
  for (const [name, button] of Object.entries(buttons))
    button.setAttribute("aria-pressed", String(name === filter));
  applyFilter();
})();
