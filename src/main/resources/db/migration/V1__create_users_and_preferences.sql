CREATE TABLE users (
    id uuid CONSTRAINT pk_users PRIMARY KEY,
    display_name varchar(60) NOT NULL,
    email varchar(254) NOT NULL,
    password_hash varchar(255) NOT NULL,
    enabled boolean NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE user_preferences (
    user_id uuid CONSTRAINT pk_user_preferences PRIMARY KEY,
    preferred_speaking_language_tag varchar(64),
    preferred_listening_language_tag varchar(64),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT fk_user_preferences_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);
