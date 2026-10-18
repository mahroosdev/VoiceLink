"use strict";

document.querySelectorAll("[data-password-toggle]").forEach((button) => {
  const input = document.getElementById(button.getAttribute("aria-controls"));
  if (!input) return;

  button.addEventListener("click", () => {
    const visible = input.type === "password";
    input.type = visible ? "text" : "password";
    const label = visible ? "Hide password" : "Show password";
    button.setAttribute("aria-label", label);
    button.setAttribute("aria-pressed", String(visible));
    button.title = label;
    button.dataset.visible = String(visible);
  });
});
