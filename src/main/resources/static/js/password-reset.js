"use strict";

const resetForm = document.getElementById("reset-password-form");
const resetToken = document.getElementById("reset-token");
const linkError = document.getElementById("reset-link-error");

if (window.location.hash) {
  const fragment = window.location.hash.slice(1);
  window.history.replaceState(null, "", window.location.pathname + window.location.search);
  const parameters = new URLSearchParams(fragment);
  const candidate = parameters.get("token");
  if (resetToken && candidate && /^[A-Za-z0-9_-]{43}$/.test(candidate)) {
    resetToken.value = candidate;
  }
}

if (resetForm && resetToken && !/^[A-Za-z0-9_-]{43}$/.test(resetToken.value)) {
  resetForm.hidden = true;
  if (linkError) linkError.hidden = false;
}
