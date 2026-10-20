(() => {
  "use strict";

  const premiumControls = document.getElementById("premium-cost-saver");
  const profileControls = [...document.querySelectorAll('input[name="profile"]')];
  if (!premiumControls || profileControls.length === 0) return;

  function update() {
    const premiumSelected = profileControls.some((control) => control.checked && control.value === "PREMIUM");
    premiumControls.hidden = !premiumSelected;
  }

  profileControls.forEach((control) => control.addEventListener("change", update));
  update();
})();
