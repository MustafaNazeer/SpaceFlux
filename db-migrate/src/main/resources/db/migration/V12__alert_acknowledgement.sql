CREATE TABLE alert_acknowledgement (
    ack_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    action VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    principal VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    acted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    note VARCHAR(500) NULL,
    PRIMARY KEY (ack_id),
    KEY ix_alert_acknowledgement_event (event_id, ack_id),
    CONSTRAINT fk_alert_acknowledgement_event FOREIGN KEY (event_id) REFERENCES alert_event (event_id),
    CONSTRAINT ck_alert_acknowledgement_action CHECK (action IN ('acknowledge', 'unacknowledge'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
