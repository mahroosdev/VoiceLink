(() => {
  "use strict";

  const premiumControls = document.getElementById("premium-cost-saver");
  const profileControls = [...document.querySelectorAll('input[name="profile"]')];
  if (premiumControls && profileControls.length > 0) {
    const updatePremium = () => {
      const selected = profileControls.some((control) => control.checked && control.value === "PREMIUM");
      premiumControls.hidden = !selected;
    };
    profileControls.forEach((control) => control.addEventListener("change", updatePremium));
    updatePremium();
  }

  const speaking = document.getElementById("speaking");
  const summary = document.getElementById("direction-preview");
  if (speaking && summary) {
    const updateDirection = () => {
      const creatorLanguage = speaking.value === "en" ? "English" : speaking.value === "ta" ? "Tamil" : "";
      const participantLanguage = speaking.value === "en" ? "Tamil" : speaking.value === "ta" ? "English" : "";
      summary.hidden = !creatorLanguage;
      summary.querySelectorAll("[data-creator-language]").forEach((node) => {
        node.textContent = creatorLanguage;
      });
      summary.querySelectorAll("[data-participant-language]").forEach((node) => {
        node.textContent = participantLanguage;
      });
    };
    speaking.addEventListener("change", updateDirection);
    updateDirection();
  }
})();
