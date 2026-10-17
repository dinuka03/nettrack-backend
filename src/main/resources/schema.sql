CREATE TABLE IF NOT EXISTS network_interval_usage (
    id                   INTEGER PRIMARY KEY,
    interface_name       TEXT NOT NULL CHECK (interface_name = 'wlp3s0'),
    rx_bytes_delta       INTEGER NOT NULL CHECK (rx_bytes_delta >= 0),
    tx_bytes_delta       INTEGER NOT NULL CHECK (tx_bytes_delta >= 0),
    duration_seconds     REAL NOT NULL CHECK (duration_seconds > 0),
    recorded_at_epoch_ms INTEGER NOT NULL
);
