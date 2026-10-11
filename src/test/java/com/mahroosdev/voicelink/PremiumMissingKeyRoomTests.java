package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.room.PremiumUnavailableException;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"voicelink.premium.enabled=true", "voicelink.premium.gemini-key="})
class PremiumMissingKeyRoomTests {
    @Autowired RoomService rooms;
    @Autowired UserAccountRepository accounts;

    @Test void enabledFlagWithoutPremiumKeyCannotCreatePremiumRoom() {
        UserAccount creator = accounts.save(new UserAccount("Creator", UUID.randomUUID() + "@example.test", "test-only-hash"));
        assertThatThrownBy(() -> rooms.createRoom(creator.getId(), "en", "ta", AiServiceProfile.PREMIUM))
                .isInstanceOf(PremiumUnavailableException.class);
        assertThat(rooms.listRoomsForMember(creator.getId())).isEmpty();
    }
}
