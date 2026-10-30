(() => {
  "use strict";
  const page = document.getElementById("room-page");
  const list = document.getElementById("conversation-events");
  const empty = document.getElementById("turn-empty");
  const latest = document.getElementById("history-latest");
  const filterStatus = document.getElementById("history-filter-status");
  const buttons = {
    translated: document.getElementById("history-translated"),
    quick: document.getElementById("history-quick")
  };
  const dots = {
    translated: document.getElementById("history-translated-unread"),
    quick: document.getElementById("history-quick-unread")
  };
  if (!page || !list || !latest || Object.values(buttons).some(button => !button)) return;
  const roomId = page.dataset.roomId || "unknown";
  const storageKey = "voicelink:unread:" + roomId;
  let filter = "translated";
  const unread = { translated: false, quick: false };

  function readStored() {
    try {
      const saved = JSON.parse(window.sessionStorage.getItem(storageKey) || "{}");
      unread.translated = saved.translated === true;
      unread.quick = saved.quick === true;
    } catch { /* Session storage is an optional enhancement. */ }
  }

  function saveUnread() {
    try { window.sessionStorage.setItem(storageKey, JSON.stringify(unread)); }
    catch { /* The in-room indicator still works for this page. */ }
  }

  function renderUnread() {
    for (const name of Object.keys(buttons)) {
      if (dots[name]) dots[name].hidden = !unread[name];
      buttons[name].setAttribute("aria-label", name === "quick"
        ? "Quick chat messages" + (unread[name] ? ", unread messages" : "")
        : "Translated messages" + (unread[name] ? ", unread messages" : ""));
    }
  }

  function markRead(name) {
    unread[name] = false;
    saveUnread();
    renderUnread();
  }

  function nearBottom() {
    return list.scrollHeight - list.scrollTop - list.clientHeight <= 80;
  }

  function applyFilter() {
    let visible = 0;
    for (const row of list.children) {
      row.hidden = row.dataset.messageKind !== filter;
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
      markRead(name);
      if (filterStatus) filterStatus.textContent = button.textContent.trim() + " selected. Unread indicator cleared.";
      latest.hidden = true;
      applyFilter();
    });
  }
  latest.addEventListener("click", () => {
    const reducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
    list.scrollTo({ top: list.scrollHeight, behavior: reducedMotion ? "auto" : "smooth" });
    latest.hidden = true;
    markRead(filter);
  });
  list.addEventListener("scroll", () => {
    if (nearBottom()) {
      latest.hidden = true;
      markRead(filter);
    }
  }, { passive: true });

  window.voicelinkHistory = {
    beforeAppend: nearBottom,
    changed(wasNearBottom, kind, ownMessage = false, provisional = false) {
      applyFilter();
      if (!provisional && !ownMessage && kind !== filter) {
        unread[kind] = true;
        saveUnread();
        renderUnread();
      }
      if (filter !== kind) return;
      if (wasNearBottom) {
        list.scrollTop = list.scrollHeight;
        latest.hidden = true;
        if (!provisional) markRead(filter);
      } else latest.hidden = false;
    },
    markRead,
    refresh: applyFilter
  };
  readStored();
  for (const [name, button] of Object.entries(buttons))
    button.setAttribute("aria-pressed", String(name === filter));
  markRead(filter);
  applyFilter();
})();
