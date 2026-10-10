package com.mahroosdev.voicelink.glossary;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/rooms/{roomId}/glossary")
public class RoomGlossaryController {
    private final RoomGlossaryService glossary;

    public RoomGlossaryController(RoomGlossaryService glossary) { this.glossary = glossary; }

    @GetMapping
    ResponseEntity<List<RoomGlossaryService.EntryView>> list(
            @AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID roomId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(glossary.list(principal.getUserId(), roomId));
    }

    @PostMapping
    ResponseEntity<RoomGlossaryService.EntryView> create(
            @AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID roomId,
            @RequestBody RoomGlossaryService.Change change) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(glossary.create(principal.getUserId(), roomId, change));
    }

    @PutMapping("/{entryId}")
    ResponseEntity<RoomGlossaryService.EntryView> update(
            @AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID roomId,
            @PathVariable UUID entryId, @RequestBody Edit change) {
        if (change == null || change.expectedVersion() == null) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(glossary.update(principal.getUserId(), roomId, entryId,
                        change.change(), change.expectedVersion()));
    }

    @DeleteMapping("/{entryId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal principal,
                                @PathVariable UUID roomId, @PathVariable UUID entryId,
                                @RequestBody Version expected) {
        if (expected == null || expected.expectedVersion() == null) throw invalid();
        glossary.delete(principal.getUserId(), roomId, entryId, expected.expectedVersion());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> error(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("error", ex.getReason() == null ? "Request could not be completed" : ex.getReason()));
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected glossary version is required");
    }

    public record Version(Long expectedVersion) {}
    public record Edit(String sourceLanguage, String targetLanguage, String sourceTerm,
                       String preferredTerm, Long expectedVersion) {
        RoomGlossaryService.Change change() {
            return new RoomGlossaryService.Change(sourceLanguage, targetLanguage, sourceTerm, preferredTerm);
        }
    }
}
