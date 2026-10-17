package com.mahroosdev.voicelink.room;

import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.ai.PremiumProviderSettings;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RoomController {
    private final RoomService rooms;
    private final PremiumProviderSettings premium;

    public RoomController(RoomService rooms, PremiumProviderSettings premium) {
        this.rooms = rooms;
        this.premium = premium;
    }

    @GetMapping("/rooms")
    String index(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        if (!model.containsAttribute("createRoomForm")) model.addAttribute("createRoomForm", new CreateRoomForm());
        model.addAttribute("rooms", rooms.listRoomsForMember(principal.getUserId()));
        model.addAttribute("premiumAvailable", premium.available());
        model.addAttribute("email", principal.getUsername());
        return "rooms";
    }

    @PostMapping("/rooms")
    String create(@AuthenticationPrincipal AccountPrincipal principal,
                  @Valid @ModelAttribute("createRoomForm") CreateRoomForm form,
                  BindingResult errors, Model model) {
        if (errors.hasErrors()) return index(principal, model);
        AiServiceProfile profile;
        try {
            profile = AiServiceProfile.valueOf(form.getProfile());
        } catch (IllegalArgumentException | NullPointerException ex) {
            errors.rejectValue("profile", "room.profile", "Choose a valid service profile.");
            return index(principal, model);
        }
        try {
            UUID id = rooms.createRoom(principal.getUserId(), form.getSpeaking(), form.getListening(), profile);
            return "redirect:/rooms/" + id;
        } catch (PremiumUnavailableException ex) {
            errors.rejectValue("profile", "room.premium", "Premium profile is not currently available.");
        } catch (IllegalArgumentException ex) {
            errors.rejectValue("listening", "room.direction", "Choose English to Tamil or Tamil to English.");
        } catch (DataIntegrityViolationException ex) {
            model.addAttribute("roomError", "Room could not be created. Please try again.");
        }
        return index(principal, model);
    }

    @PostMapping("/rooms/join")
    String join(@AuthenticationPrincipal AccountPrincipal principal,
                @RequestParam(name = "joinCode", required = false) String joinCode,
                RedirectAttributes redirect) {
        try {
            return "redirect:/rooms/" + rooms.joinRoom(principal.getUserId(), joinCode);
        } catch (RoomInviteUnavailableException | DataIntegrityViolationException ex) {
            redirect.addFlashAttribute("joinError", "Invite unavailable. Check the code or ask for a new room.");
            return "redirect:/rooms";
        }
    }

    @GetMapping("/rooms/{id}")
    String show(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("room", rooms.getRoomForMember(principal.getUserId(), id));
        model.addAttribute("email", principal.getUsername());
        return "room";
    }

    @PostMapping("/rooms/{id}/close")
    String close(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        rooms.closeRoom(principal.getUserId(), id);
        return "redirect:/rooms/" + id;
    }
}
