CREATE TABLE room_glossary_entries (
    id uuid CONSTRAINT pk_room_glossary_entries PRIMARY KEY,
    room_id uuid NOT NULL,
    source_language_tag varchar(64) NOT NULL,
    target_language_tag varchar(64) NOT NULL,
    source_term varchar(192) NOT NULL,
    normalized_source_term varchar(192) NOT NULL,
    preferred_term varchar(256) NOT NULL,
    created_by_user_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_room_glossary_room FOREIGN KEY (room_id)
        REFERENCES conversation_rooms (id) ON DELETE CASCADE,
    CONSTRAINT fk_room_glossary_creator FOREIGN KEY (created_by_user_id)
        REFERENCES users (id),
    CONSTRAINT uq_room_glossary_source UNIQUE
        (room_id, source_language_tag, target_language_tag, normalized_source_term),
    CONSTRAINT ck_room_glossary_languages CHECK (source_language_tag <> target_language_tag),
    CONSTRAINT ck_room_glossary_terms CHECK
        (length(trim(source_term)) > 0 AND length(trim(normalized_source_term)) > 0
         AND length(trim(preferred_term)) > 0),
    CONSTRAINT ck_room_glossary_version CHECK (row_version >= 0)
);

CREATE INDEX ix_room_glossary_room ON room_glossary_entries (room_id);
