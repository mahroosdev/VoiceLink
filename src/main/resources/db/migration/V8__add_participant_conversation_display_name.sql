ALTER TABLE room_participants
    ADD COLUMN conversation_display_name VARCHAR(48);

ALTER TABLE room_participants
    ADD CONSTRAINT chk_participant_conversation_display_name
    CHECK (
        conversation_display_name IS NULL
        OR (
            CHAR_LENGTH(conversation_display_name) BETWEEN 1 AND 48
            AND conversation_display_name = TRIM(conversation_display_name)
        )
    );
