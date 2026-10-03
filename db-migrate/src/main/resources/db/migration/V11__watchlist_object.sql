CREATE TABLE watchlist_object (
    catalog_number INT UNSIGNED NOT NULL,
    name VARCHAR(64) NOT NULL,
    rules_version INT UNSIGNED NOT NULL,
    PRIMARY KEY (catalog_number)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
