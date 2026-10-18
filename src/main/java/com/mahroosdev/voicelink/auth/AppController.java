package com.mahroosdev.voicelink.auth;

import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.room.RoomStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AppController {
    private final RoomService rooms;

    public AppController(RoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping("/app")
    String app(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("currentRooms", rooms.listRoomsForMember(principal.getUserId()).stream()
                .filter(room -> room.status() == RoomStatus.WAITING || room.status() == RoomStatus.ACTIVE)
                .toList());
        model.addAttribute("email", principal.getUsername());
        return "app";
    }
}
