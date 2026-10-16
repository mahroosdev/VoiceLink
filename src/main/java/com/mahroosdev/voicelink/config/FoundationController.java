package com.mahroosdev.voicelink.config;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.ai.PremiumProviderSettings;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FoundationController {
    private final PremiumProviderSettings premium;

    public FoundationController(PremiumProviderSettings premium) {
        this.premium = premium;
    }


    @GetMapping("/")
    public String foundation() {
        return "foundation";
    }

    @GetMapping("/about")
    public String about(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("signedIn", principal != null);
        model.addAttribute("premiumAvailable", premium.available());
        if (principal != null) {
            model.addAttribute("email", principal.getUsername());
        }
        return "about";
    }
}
