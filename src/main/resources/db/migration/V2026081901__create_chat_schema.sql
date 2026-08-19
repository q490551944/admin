CREATE TABLE chat_conversation (
    id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    name VARCHAR(50) NULL,
    normalized_name VARCHAR(50) NULL,
    direct_key VARCHAR(64) NULL,
    owner_id BIGINT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_message_id BIGINT NULL,
    last_activity_at TIMESTAMP(6) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    dissolved_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_conversation_owner FOREIGN KEY (owner_id) REFERENCES user (id),
    CONSTRAINT uk_chat_conversation_room_name UNIQUE (type, normalized_name),
    CONSTRAINT uk_chat_conversation_direct_key UNIQUE (type, direct_key),
    CONSTRAINT ck_chat_conversation_type CHECK (type IN ('PUBLIC_ROOM', 'DIRECT_MESSAGE')),
    CONSTRAINT ck_chat_conversation_status CHECK (status IN ('ACTIVE', 'DISSOLVED')),
    CONSTRAINT ck_chat_conversation_shape CHECK (
        (type = 'PUBLIC_ROOM' AND name IS NOT NULL AND normalized_name IS NOT NULL AND owner_id IS NOT NULL AND direct_key IS NULL)
        OR
        (type = 'DIRECT_MESSAGE' AND name IS NULL AND normalized_name IS NULL AND owner_id IS NULL AND direct_key IS NOT NULL)
    )
);

CREATE TABLE chat_participant (
    id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_participant_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_chat_participant_user FOREIGN KEY (user_id) REFERENCES user (id),
    CONSTRAINT uk_chat_participant UNIQUE (conversation_id, user_id)
);

CREATE TABLE chat_message (
    id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    message_type VARCHAR(16) NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    body VARCHAR(5000) NULL,
    attachment_id BIGINT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SENDING',
    content_hash CHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_chat_message_sender FOREIGN KEY (sender_id) REFERENCES user (id),
    CONSTRAINT uk_chat_message_request UNIQUE (sender_id, client_request_id),
    CONSTRAINT ck_chat_message_type CHECK (message_type IN ('TEXT', 'IMAGE')),
    CONSTRAINT ck_chat_message_status CHECK (status IN ('SENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_chat_message_content CHECK (
        (message_type = 'TEXT' AND body IS NOT NULL AND attachment_id IS NULL)
        OR
        (message_type = 'IMAGE' AND body IS NULL AND attachment_id IS NOT NULL)
    )
);

CREATE TABLE chat_attachment (
    id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NULL,
    uploader_id BIGINT NOT NULL,
    storage_bucket VARCHAR(128) NOT NULL,
    storage_object_key VARCHAR(512) NOT NULL,
    orig_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INT NULL,
    height INT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_attachment_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_chat_attachment_message FOREIGN KEY (message_id) REFERENCES chat_message (id),
    CONSTRAINT fk_chat_attachment_uploader FOREIGN KEY (uploader_id) REFERENCES user (id),
    CONSTRAINT uk_chat_attachment_object UNIQUE (storage_bucket, storage_object_key),
    CONSTRAINT uk_chat_attachment_message UNIQUE (message_id),
    CONSTRAINT ck_chat_attachment_status CHECK (status IN ('UPLOADING', 'READY', 'ATTACHED', 'FAILED')),
    CONSTRAINT ck_chat_attachment_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_chat_attachment_dimensions CHECK (
        (width IS NULL AND height IS NULL) OR (width > 0 AND height > 0)
    )
);

ALTER TABLE chat_message
    ADD CONSTRAINT fk_chat_message_attachment FOREIGN KEY (attachment_id) REFERENCES chat_attachment (id);

ALTER TABLE chat_conversation
    ADD CONSTRAINT fk_chat_conversation_last_message FOREIGN KEY (last_message_id) REFERENCES chat_message (id);

CREATE TABLE chat_audit_event (
    id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_user_id BIGINT NULL,
    conversation_id BIGINT NULL,
    message_id BIGINT NULL,
    payload_json TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_audit_actor FOREIGN KEY (actor_user_id) REFERENCES user (id),
    CONSTRAINT fk_chat_audit_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_chat_audit_message FOREIGN KEY (message_id) REFERENCES chat_message (id)
);

CREATE INDEX idx_chat_conversation_activity ON chat_conversation (status, last_activity_at, id);
CREATE INDEX idx_chat_participant_user ON chat_participant (user_id, deleted_at, conversation_id);
CREATE INDEX idx_chat_message_history ON chat_message (conversation_id, created_at, id);
CREATE INDEX idx_chat_attachment_conversation_status ON chat_attachment (conversation_id, status, created_at);
CREATE INDEX idx_chat_attachment_cleanup ON chat_attachment (status, expires_at);
CREATE INDEX idx_chat_audit_event_lookup ON chat_audit_event (conversation_id, created_at, id);
