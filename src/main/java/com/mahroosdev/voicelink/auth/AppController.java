package com.mahroosdev.voicelink.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AppController {
    @GetMapping("/app")
    String app(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("email", principal.getUsername());
        return "app";
    }
}
