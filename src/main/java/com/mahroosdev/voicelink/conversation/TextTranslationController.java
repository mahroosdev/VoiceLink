package com.mahroosdev.voicelink.conversation;

import java.util.Map;
import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TextTranslationController {
    private final TextTranslationService service;

    public TextTranslationController(TextTranslationService service) { this.service = service; }

    @PostMapping("/api/rooms/{roomId}/text/reviews")
    ResponseEntity<TextTranslationService.ReviewView> review(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID roomId, @RequestBody ReviewRequest body, HttpServletRequest request) {
        if (body == null || body.revision() == null) throw badRequest();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
                service.review(principal.getUserId(), sessionId(request), roomId,
                        body.sourceText(), body.revision()));
    }

    @PostMapping("/api/rooms/{roomId}/text/send")
    ResponseEntity<TextTranslationService.SentView> send(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID roomId, @RequestBody SendRequest body, HttpServletRequest request) {
        if (body == null || body.revision() == null) throw badRequest();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                service.send(principal.getUserId(), sessionId(request), roomId,
                        body.reviewId(), body.sourceText(), body.revision()));
    }

    private static String sessionId(HttpServletRequest request) {
        if (request.getSession(false) == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return request.getSession(false).getId();
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid text translation request.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> error(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("error", ex.getReason() == null ? "Request could not be completed." : ex.getReason()));
    }

    public record ReviewRequest(String sourceText, Long revision) {}
    public record SendRequest(UUID reviewId, String sourceText, Long revision) {}
}
