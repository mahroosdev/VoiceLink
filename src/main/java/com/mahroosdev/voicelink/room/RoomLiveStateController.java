package com.mahroosdev.voicelink.room;

import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoomLiveStateController {
    private final RoomLiveAuthorizer authorizer;

    public RoomLiveStateController(RoomLiveAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @GetMapping("/api/rooms/{id}/live-state")
    RoomLiveAuthorizer.Snapshot state(@AuthenticationPrincipal AccountPrincipal principal,
                                       @PathVariable UUID id) {
        return authorizer.snapshot(principal.getUserId(), id);
    }
}
