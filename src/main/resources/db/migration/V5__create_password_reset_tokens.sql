CREATE TABLE password_reset_tokens (
    user_id uuid CONSTRAINT pk_password_reset_tokens PRIMARY KEY,
    token_hash varchar(64) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_password_reset_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_tokens_hash_length CHECK (length(token_hash) = 64),
    CONSTRAINT ck_password_reset_tokens_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_password_reset_tokens_expires_at ON password_reset_tokens (expires_at);
