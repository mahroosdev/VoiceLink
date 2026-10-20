ALTER TABLE conversation_rooms
    ADD COLUMN premium_cost_saver_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE conversation_rooms
    ADD CONSTRAINT ck_conversation_rooms_standard_cost_saver
    CHECK (service_profile = 'PREMIUM' OR premium_cost_saver_enabled);
