ALTER TABLE conversation_rooms
    ADD COLUMN service_profile varchar(16) NOT NULL DEFAULT 'STANDARD';

ALTER TABLE conversation_rooms
    ADD CONSTRAINT conversation_rooms_service_profile_check
    CHECK (service_profile IN ('STANDARD', 'PREMIUM'));
