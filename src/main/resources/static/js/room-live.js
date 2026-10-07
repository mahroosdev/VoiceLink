(() => {
  const page = document.getElementById("room-page");
  if (!page) return;
  const roomId = page.dataset.roomId;
  const status = document.getElementById("room-status");
  const connection = document.getElementById("connection-state");
  const members = document.getElementById("participants-list");
  const waiting = document.getElementById("waiting-section");
  const active = document.getElementById("active-section");
  const live = document.getElementById("live-text-section");
  const notice = document.getElementById("closed-notice");
  const closeForm = document.getElementById("close-room-form");
  const closeButton = closeForm?.querySelector("button");
  const form = document.getElementById("live-text-form");
  const input = document.getElementById("live-text-input");
  const send = document.getElementById("live-send");
  const events = document.getElementById("live-events");
  let socket;
  let retry = 0;
  let stopped = false;
  let streamId;
  let lastSequence = 0;
  const seen = new Set();

  function setStatus(value) {
    status.textContent = value;
    waiting.hidden = value !== "WAITING";
    active.hidden = value !== "ACTIVE";
    live.hidden = value !== "ACTIVE";
    notice.hidden = value !== "CLOSED";
    if (closeForm) closeForm.hidden = value === "CLOSED";
    if (closeButton) closeButton.textContent =
      value === "WAITING" ? "Cancel room" : "Leave and close room";
    input.disabled = value !== "ACTIVE" || !socket || socket.readyState !== WebSocket.OPEN;
    send.disabled = input.disabled;
    if (value === "CLOSED") stopped = true;
  }

  function showParticipants(people) {
    if (!Array.isArray(people)) return;
    members.replaceChildren();
    for (const person of people) {
      const item = document.createElement("li");
      item.textContent = `${person.displayName} — speaks ${person.speaking}, receives ${person.listening}`;
      members.append(item);
    }
  }

  function addText(payload) {
    const item = document.createElement("li");
    item.textContent = payload.text;
    events.append(item);
  }

  function receive(raw) {
    let event;
    try { event = JSON.parse(raw); } catch { return; }
    if (event.protocolVersion !== 1 || event.roomId !== roomId) return;
    if (streamId !== event.streamId) {
      streamId = event.streamId;
      lastSequence = 0;
      seen.clear();
    }
    if (event.sequence <= lastSequence || seen.has(event.eventId)) return;
    lastSequence = event.sequence;
    seen.add(event.eventId);
    if (seen.size > 256) seen.delete(seen.values().next().value);
    if (event.type === "ROOM_STATE") {
      setStatus(event.payload.status);
      showParticipants(event.payload.participants);
    } else if (event.type === "TEXT_MESSAGE") {
      addText(event.payload);
    } else if (event.type === "ROOM_CLOSED") {
      setStatus("CLOSED");
      connection.textContent = "Live connection closed";
      socket.close();
    } else if (event.type === "ERROR") {
      connection.textContent = event.payload.message || "Live message rejected";
    }
  }

  async function mayReconnect() {
    try {
      const response = await fetch(`/api/rooms/${roomId}/live-state`, {
        credentials: "same-origin", headers: { Accept: "application/json" }
      });
      if (response.redirected || response.status === 401 || response.status === 403 ||
          response.status === 404) return false;
      if (!response.ok) return true;
      const state = await response.json();
      if (state.status === "CLOSED") {
        setStatus("CLOSED");
        connection.textContent = "Live connection closed";
        return false;
      }
      return true;
    } catch { return true; }
  }

  async function connect() {
    if (stopped) return;
    connection.textContent = retry ? "Disconnected / Reconnecting" : "Connecting";
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(`${scheme}//${location.host}/ws/rooms/${roomId}`);
    socket.onopen = () => {
      retry = 0;
      connection.textContent = "Connected";
      setStatus(status.textContent);
    };
    socket.onmessage = message => receive(message.data);
    socket.onclose = async event => {
      input.disabled = true;
      send.disabled = true;
      if (stopped) return;
      if (event.code === 4001) {
        stopped = true;
        connection.textContent = "Live connection moved to a newer room tab";
        return;
      }
      connection.textContent = "Disconnected / Reconnecting; missed text is unavailable";
      if (!(await mayReconnect())) {
        stopped = true;
        if (status.textContent !== "CLOSED") connection.textContent = "Live access ended. Sign in or reopen the room page.";
        return;
      }
      const delay = Math.min(15000, 1000 * (2 ** Math.min(retry++, 4)));
      setTimeout(connect, delay);
    };
  }

  form.addEventListener("submit", event => {
    event.preventDefault();
    if (!socket || socket.readyState !== WebSocket.OPEN || status.textContent !== "ACTIVE") return;
    const text = input.value.trim();
    if (!text || [...text].length > 1000) return;
    socket.send(JSON.stringify({
      protocolVersion: 1, type: "SEND_TEXT", clientMessageId: crypto.randomUUID(), text
    }));
    input.value = "";
  });
  connect();
})();
