package com.mahroosdev.voicelink.auth;

import com.mahroosdev.voicelink.user.EmailNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class PasswordResetController {
    private final PasswordResetService resets;
    private final PasswordResetSettings settings;
    private final EmailNormalizer emails;
    private final Validator validator;

    public PasswordResetController(PasswordResetService resets, PasswordResetSettings settings,
                                   EmailNormalizer emails, Validator validator) {
        this.resets = resets;
        this.settings = settings;
        this.emails = emails;
        this.validator = validator;
    }

    @GetMapping("/forgot-password")
    String forgot(Model model) {
        requireEnabled();
        model.addAttribute("forgotPasswordForm", new ForgotPasswordForm());
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    String request(@ModelAttribute ForgotPasswordForm forgotPasswordForm,
                   BindingResult result, Model model) {
        requireEnabled();
        forgotPasswordForm.setEmail(emails.normalize(forgotPasswordForm.getEmail()));
        validator.validate(forgotPasswordForm).forEach(violation -> result.rejectValue(
                violation.getPropertyPath().toString(), "invalid", violation.getMessage()));
        if (result.hasErrors()) return "forgot-password";
        resets.request(forgotPasswordForm.getEmail());
        model.addAttribute("acknowledged", true);
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    String resetForm(Model model, HttpServletResponse response) {
        requireEnabled();
        privateHeaders(response);
        model.addAttribute("resetPasswordForm", new ResetPasswordForm());
        return "reset-password";
    }

    @PostMapping("/reset-password")
    String reset(@Valid @ModelAttribute ResetPasswordForm resetPasswordForm,
                 BindingResult result, Model model, HttpServletRequest request,
                 HttpServletResponse response) {
        requireEnabled();
        privateHeaders(response);
        if (resetPasswordForm.getConfirmPassword() == null
                || !resetPasswordForm.getConfirmPassword().equals(resetPasswordForm.getNewPassword())) {
            result.rejectValue("confirmPassword", "mismatch", "Passwords must match.");
        }
        if (result.hasErrors()) {
            resetPasswordForm.clearPasswords();
            return "reset-password";
        }
        if (!resets.reset(resetPasswordForm.getToken(), resetPasswordForm.getNewPassword())) {
            resetPasswordForm.clearPasswords();
            resetPasswordForm.setToken(null);
            model.addAttribute("invalidToken", true);
            return "reset-password";
        }
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return "redirect:/login?reset";
    }

    private void requireEnabled() {
        if (!settings.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private void privateHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
