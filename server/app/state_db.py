from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path
from threading import Lock
from typing import Iterator

from app.config import get_state_db_path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs (
    job_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    phase TEXT NOT NULL DEFAULT 'queued',
    progress INTEGER NOT NULL,
    message TEXT NOT NULL,
    video_url TEXT,
    error TEXT,
    request_payload TEXT,
    worker_id TEXT,
    started_at TEXT,
    heartbeat_at TEXT,
    lease_expires_at TEXT,
    finished_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_jobs_status_created_at ON jobs(status, created_at);

CREATE TABLE IF NOT EXISTS uploads (
    file_id TEXT PRIMARY KEY,
    filename TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    media_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_uploads_created_at ON uploads(created_at);
"""

_initialized_paths: set[str] = set()
_init_lock = Lock()
_JOB_COLUMN_MIGRATIONS = {
    "phase": "ALTER TABLE jobs ADD COLUMN phase TEXT NOT NULL DEFAULT 'queued'",
    "heartbeat_at": "ALTER TABLE jobs ADD COLUMN heartbeat_at TEXT",
    "lease_expires_at": "ALTER TABLE jobs ADD COLUMN lease_expires_at TEXT",
}


def _normalized_db_path(path: str | None = None) -> str:
    value = path or get_state_db_path()
    return str(Path(value).expanduser().resolve())


def _connect(path: str | None = None) -> sqlite3.Connection:
    db_path = _normalized_db_path(path)
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(db_path, timeout=30, isolation_level=None)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def initialize_database(path: str | None = None) -> str:
    db_path = _normalized_db_path(path)
    with _init_lock:
        if db_path in _initialized_paths:
            return db_path
        with _connect(db_path) as connection:
            connection.executescript(_SCHEMA)
            columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(jobs)").fetchall()
            }
            for column, statement in _JOB_COLUMN_MIGRATIONS.items():
                if column not in columns:
                    connection.execute(statement)
            connection.execute("CREATE INDEX IF NOT EXISTS idx_jobs_status_created_at ON jobs(status, created_at)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_jobs_lease_expires_at ON jobs(lease_expires_at)")
        _initialized_paths.add(db_path)
    return db_path


@contextmanager
def get_connection(path: str | None = None) -> Iterator[sqlite3.Connection]:
    db_path = initialize_database(path)
    connection = _connect(db_path)
    try:
        yield connection
    finally:
        connection.close()
