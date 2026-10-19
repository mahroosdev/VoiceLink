package com.mahroosdev.voicelink.conversation;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SpeechTurnController {
    private final SpeechTurnService turns;

    public SpeechTurnController(SpeechTurnService turns) { this.turns = turns; }

    @PostMapping(path = "/api/rooms/{roomId}/turns", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<SpeechTurnService.Accepted> upload(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID roomId, @RequestParam UUID clientRequestId,
            @RequestParam long durationMillis,
            @RequestParam(defaultValue = "LIVE_SEND") SpeechTurnService.Workflow workflow,
            @RequestPart("audio") MultipartFile audio, HttpServletRequest request) {
        try {
            var accepted = turns.accept(principal.getUserId(), sessionId(request),
                    roomId, clientRequestId, workflow, audio.getBytes(), audio.getContentType(), durationMillis);
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(accepted);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audio upload");
        }
    }

    @GetMapping("/api/rooms/{roomId}/turns/{turnId}/review")
    ResponseEntity<SpeechTurnService.Review> review(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID roomId, @PathVariable UUID turnId, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(turns.review(principal.getUserId(), sessionId(request), roomId, turnId));
    }

    @PostMapping("/api/rooms/{roomId}/turns/{turnId}/send")
    ResponseEntity<SpeechTurnService.Sent> send(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID roomId, @PathVariable UUID turnId, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(turns.send(principal.getUserId(), sessionId(request), roomId, turnId));
    }

    @DeleteMapping("/api/rooms/{roomId}/turns/{turnId}/review")
    ResponseEntity<Void> cancel(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID roomId, @PathVariable UUID turnId, HttpServletRequest request) {
        turns.cancel(principal.getUserId(), sessionId(request), roomId, turnId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static String sessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        return session.getId();
    }

    @GetMapping("/api/rooms/{roomId}/turns/recent")
    ResponseEntity<List<SpeechTurnService.Snapshot>> recent(
            @AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID roomId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(turns.recent(principal.getUserId(), roomId));
    }

    @GetMapping("/api/rooms/{roomId}/turns/{turnId}/audio")
    ResponseEntity<byte[]> audio(@AuthenticationPrincipal AccountPrincipal principal,
                                 @PathVariable UUID roomId, @PathVariable UUID turnId) {
        var audio = turns.audio(principal.getUserId(), roomId, turnId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, audio.mediaType())
                .cacheControl(CacheControl.noStore()).body(audio.bytes());
    }
}
