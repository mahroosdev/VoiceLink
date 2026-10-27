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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        if (!model.containsAttribute("joinCodeForm")) model.addAttribute("joinCodeForm", new JoinCodeForm());
        model.addAttribute("closedRooms", rooms.listRoomsForMember(principal.getUserId()).stream()
                .filter(room -> room.status() == RoomStatus.CLOSED).toList());
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
            boolean costSaver = true;
            if (profile == AiServiceProfile.PREMIUM) {
                if (!"ON".equals(form.getPremiumCostSaver()) && !"OFF".equals(form.getPremiumCostSaver())) {
                    errors.rejectValue("premiumCostSaver", "room.costSaver", "Choose API Cost Saver ON or OFF.");
                    return index(principal, model);
                }
                costSaver = "ON".equals(form.getPremiumCostSaver());
            }
            UUID id = rooms.createRoomForSpeaker(principal.getUserId(), form.getDisplayName(),
                    form.getSpeaking(), profile, costSaver);
            return "redirect:/rooms/" + id;
        } catch (PremiumUnavailableException ex) {
            errors.rejectValue("profile", "room.premium", "Premium profile is not currently available.");
        } catch (IllegalArgumentException ex) {
            if (invalidDisplayName(form.getDisplayName())) {
                errors.rejectValue("displayName", "room.displayName", ConversationDisplayName.VALIDATION_MESSAGE);
            } else {
                errors.rejectValue("speaking", "room.direction", "Choose English or Tamil.");
            }
        } catch (DataIntegrityViolationException ex) {
            model.addAttribute("roomError", "Room could not be created. Please try again.");
        }
        return index(principal, model);
    }

    @PostMapping({"/rooms/join", "/rooms/join/preview"})
    String previewJoin(@AuthenticationPrincipal AccountPrincipal principal,
                       @Valid @ModelAttribute("joinCodeForm") JoinCodeForm form,
                       BindingResult errors, Model model) {
        if (errors.hasErrors()) return index(principal, model);
        try {
            RoomService.JoinPreview preview = rooms.previewJoin(principal.getUserId(), form.getJoinCode());
            if (preview.alreadyMember()) return "redirect:/rooms/" + preview.roomId();
            JoinRoomForm joinRoomForm = new JoinRoomForm();
            joinRoomForm.setJoinCode(preview.joinCode());
            joinRoomForm.setSpeaking(preview.participantSpeaking());
            model.addAttribute("joinRoomForm", joinRoomForm);
            model.addAttribute("joinPreview", preview);
        } catch (RoomInviteUnavailableException ex) {
            model.addAttribute("joinError", "Invite unavailable. Check the code or ask for a new room.");
        }
        return index(principal, model);
    }

    @PostMapping("/rooms/join/setup")
    String joinWithSetup(@AuthenticationPrincipal AccountPrincipal principal,
                         @Valid @ModelAttribute("joinRoomForm") JoinRoomForm form,
                         BindingResult errors, Model model, RedirectAttributes redirect) {
        RoomService.JoinPreview preview;
        try {
            preview = rooms.previewJoin(principal.getUserId(), form.getJoinCode());
            if (preview.alreadyMember()) return "redirect:/rooms/" + preview.roomId();
        } catch (RoomInviteUnavailableException ex) {
            redirect.addFlashAttribute("joinError", "Invite unavailable. Check the code or ask for a new room.");
            return "redirect:/rooms";
        }
        if (errors.hasErrors()) {
            model.addAttribute("joinPreview", preview);
            return index(principal, model);
        }
        try {
            return "redirect:/rooms/" + rooms.joinRoomWithSetup(principal.getUserId(),
                    form.getJoinCode(), form.getDisplayName(), form.getSpeaking());
        } catch (JoinLanguageMismatchException ex) {
            errors.rejectValue("speaking", "room.direction", "Choose the available language for this conversation.");
            model.addAttribute("joinPreview", preview);
            return index(principal, model);
        } catch (IllegalArgumentException ex) {
            errors.rejectValue("displayName", "room.displayName", ConversationDisplayName.VALIDATION_MESSAGE);
            model.addAttribute("joinPreview", preview);
            return index(principal, model);
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

    @PostMapping(path = "/api/rooms/{id}/languages", consumes = "application/json")
    @ResponseBody
    ResponseEntity<Void> updateLanguages(@AuthenticationPrincipal AccountPrincipal principal,
                                         @PathVariable UUID id, @RequestBody LanguageUpdateRequest request) {
        if (request == null || request.ownerSpeaking() == null || request.ownerSpeaking().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose English or Tamil.");
        }
        try {
            rooms.updateLanguages(principal.getUserId(), id, request.ownerSpeaking());
        } catch (IllegalArgumentException ex) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose English or Tamil.");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rooms/{id}/remove")
    String remove(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id,
                  RedirectAttributes redirect) {
        rooms.removeClosedRoom(principal.getUserId(), id);
        redirect.addFlashAttribute("roomRemoved", "Closed room removed from your list.");
        return "redirect:/rooms";
    }

    private boolean invalidDisplayName(String value) {
        try {
            ConversationDisplayName.normalize(value);
            return false;
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    public record LanguageUpdateRequest(String ownerSpeaking) {}
}
