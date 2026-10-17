"use strict";

const deleteForm = document.getElementById("delete-account-form");
if (deleteForm) {
  const password = document.getElementById("currentPassword");
  const confirmation = document.getElementById("confirmation");
  const submit = document.getElementById("delete-account-submit");
  const update = () => {
    submit.disabled = password.value.length === 0 || confirmation.value !== "DELETE";
  };
  password.addEventListener("input", update);
  confirmation.addEventListener("input", update);
  update();
}
