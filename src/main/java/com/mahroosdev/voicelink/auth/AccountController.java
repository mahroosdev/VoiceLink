package com.mahroosdev.voicelink.auth;

import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AccountController {
    private final UserAccountRepository accounts;
    private final AccountDeletionService deletion;

    public AccountController(UserAccountRepository accounts, AccountDeletionService deletion) {
        this.accounts = accounts;
        this.deletion = deletion;
    }

    @GetMapping("/account")
    String account(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("account", accounts.findById(principal.getUserId()).orElseThrow());
        model.addAttribute("email", principal.getUsername());
        return "account";
    }

    @GetMapping("/account/delete")
    String confirmation(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("email", principal.getUsername());
        return "delete-account";
    }

    @PostMapping("/account/delete")
    String delete(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(name = "currentPassword", required = false) String currentPassword,
            @RequestParam(name = "confirmation", required = false) String confirmation, Model model) {
        try {
            if (deletion.delete(principal.getUserId(), currentPassword, confirmation)) {
                return "redirect:/login?deleted";
            }
            model.addAttribute("deleteError", "Check your current password and type DELETE exactly.");
        } catch (RuntimeException exception) {
            model.addAttribute("deleteError", "Account deletion could not be completed. Please try again.");
        }
        model.addAttribute("email", principal.getUsername());
        return "delete-account";
    }
}
