CREATE TABLE conversation_rooms (
    id uuid CONSTRAINT pk_conversation_rooms PRIMARY KEY,
    join_code varchar(16) NOT NULL,
    status varchar(7) NOT NULL,
    created_by_user_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    invite_expires_at timestamp with time zone NOT NULL,
    closed_at timestamp with time zone,
    CONSTRAINT uq_conversation_rooms_join_code UNIQUE (join_code),
    CONSTRAINT fk_conversation_rooms_creator FOREIGN KEY (created_by_user_id)
        REFERENCES users (id),
    CONSTRAINT ck_conversation_rooms_status CHECK (status IN ('WAITING', 'ACTIVE', 'CLOSED')),
    CONSTRAINT ck_conversation_rooms_expiry CHECK (invite_expires_at > created_at),
    CONSTRAINT ck_conversation_rooms_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE TABLE room_participants (
    id uuid CONSTRAINT pk_room_participants PRIMARY KEY,
    room_id uuid NOT NULL,
    user_id uuid NOT NULL,
    participant_slot smallint NOT NULL,
    speaking_language_tag varchar(64) NOT NULL,
    listening_language_tag varchar(64) NOT NULL,
    joined_at timestamp with time zone NOT NULL,
    CONSTRAINT fk_room_participants_room FOREIGN KEY (room_id)
        REFERENCES conversation_rooms (id),
    CONSTRAINT fk_room_participants_user FOREIGN KEY (user_id)
        REFERENCES users (id),
    CONSTRAINT uq_room_participants_room_user UNIQUE (room_id, user_id),
    CONSTRAINT uq_room_participants_room_slot UNIQUE (room_id, participant_slot),
    CONSTRAINT ck_room_participants_slot CHECK (participant_slot IN (1, 2)),
    CONSTRAINT ck_room_participants_languages CHECK (speaking_language_tag <> listening_language_tag)
);

CREATE INDEX ix_room_participants_user_joined
    ON room_participants (user_id, joined_at DESC);
