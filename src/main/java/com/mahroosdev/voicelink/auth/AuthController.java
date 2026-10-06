package com.mahroosdev.voicelink.auth;

import com.mahroosdev.voicelink.user.EmailNormalizer;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AuthController {
    private final RegistrationService registration;
    private final EmailNormalizer emails;
    private final Validator validator;

    public AuthController(RegistrationService registration, EmailNormalizer emails, Validator validator) {
        this.registration = registration;
        this.emails = emails;
        this.validator = validator;
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping("/register")
    String registrationForm(Model model) {
        model.addAttribute("registrationForm", new RegistrationForm());
        return "register";
    }

    @PostMapping("/register")
    String register(@ModelAttribute RegistrationForm registrationForm,
            BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        registrationForm.setDisplayName(registrationForm.getDisplayName() == null
                ? "" : registrationForm.getDisplayName().strip());
        registrationForm.setEmail(emails.normalize(registrationForm.getEmail()));
        validator.validate(registrationForm).forEach(violation -> result.rejectValue(
                violation.getPropertyPath().toString(), "invalid", violation.getMessage()));
        if (result.hasErrors()) {
            registrationForm.setPassword(null);
            return "register";
        }
        try {
            registration.register(registrationForm);
            redirectAttributes.addFlashAttribute("registrationSuccess",
                    "Account created successfully. You can now sign in.");
            return "redirect:/login";
        } catch (DuplicateRegistrationException exception) {
            registrationForm.setPassword(null);
            model.addAttribute("registrationError", "Registration could not be completed with those details.");
            return "register";
        } catch (ConstraintViolationException exception) {
            registrationForm.setPassword(null);
            model.addAttribute("registrationError", "Please correct the registration details and try again.");
            return "register";
        }
    }
}
