CREATE TABLE screening_run_rejected (
    run_alert_seq BIGINT UNSIGNED NOT NULL,
    position INT UNSIGNED NOT NULL,
    catalog_number INT UNSIGNED NOT NULL,
    name VARCHAR(64) NULL,
    role VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason TEXT NOT NULL,
    PRIMARY KEY (run_alert_seq, position),
    KEY ix_screening_run_rejected_catalog (catalog_number, run_alert_seq),
    CONSTRAINT fk_screening_run_rejected_run FOREIGN KEY (run_alert_seq) REFERENCES screening_run (alert_seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
