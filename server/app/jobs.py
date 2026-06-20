from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Optional
from uuid import uuid4

from app.config import WORKER_LEASE_SECONDS
from app.state_db import get_connection


class JobStatus(str, Enum):
    queued = "queued"
    running = "running"
    done = "done"
    failed = "failed"


@dataclass
class JobInfo:
    job_id: str
    status: JobStatus
    phase: str
    progress: int
    message: str
    video_url: Optional[str]
    error: Optional[str]
    worker_id: Optional[str]
    heartbeat_at: Optional[datetime]
    lease_expires_at: Optional[datetime]
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class GenerationHistoryRecord:
    job_id: str
    prompt: str
    video_url: str
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class ClaimedJob:
    job_id: str
    request_payload: str


def _utcnow() -> datetime:
    return datetime.utcnow()


def _to_optional_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = str(value)
    return text or None


def _parse_job_status(value: str) -> JobStatus:
    try:
        return JobStatus(value)
    except ValueError:
        return JobStatus.failed


def _parse_optional_datetime(value: Any) -> Optional[datetime]:
    text = _to_optional_text(value)
    if not text:
        return None
    return datetime.fromisoformat(text)


def _row_to_job(row) -> JobInfo:
    return JobInfo(
        job_id=row["job_id"],
        status=_parse_job_status(row["status"]),
        phase=_to_optional_text(row["phase"]) or "queued",
        progress=int(row["progress"]),
        message=row["message"],
        video_url=_to_optional_text(row["video_url"]),
        error=_to_optional_text(row["error"]),
        worker_id=_to_optional_text(row["worker_id"]),
        heartbeat_at=_parse_optional_datetime(row["heartbeat_at"]),
        lease_expires_at=_parse_optional_datetime(row["lease_expires_at"]),
        created_at=datetime.fromisoformat(row["created_at"]),
        updated_at=datetime.fromisoformat(row["updated_at"]),
    )


def create_job(request_payload: dict[str, Any] | str | None = None) -> JobInfo:
    now = datetime.utcnow()
    job_id = str(uuid4())
    job = JobInfo(
        job_id=job_id,
        status=JobStatus.queued,
        phase="queued",
        progress=0,
        message="Queued",
        video_url=None,
        error=None,
        worker_id=None,
        heartbeat_at=None,
        lease_expires_at=None,
        created_at=now,
        updated_at=now,
    )
    serialized_payload: Optional[str]
    if request_payload is None:
        serialized_payload = None
    elif isinstance(request_payload, str):
        serialized_payload = request_payload
    else:
        serialized_payload = json.dumps(request_payload, ensure_ascii=False)
    with get_connection() as connection:
        connection.execute(
            """
            INSERT INTO jobs (
                job_id, status, phase, progress, message, video_url, error, request_payload, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                job.job_id,
                job.status.value,
                job.phase,
                job.progress,
                job.message,
                job.video_url,
                job.error,
                serialized_payload,
                job.created_at.isoformat(),
                job.updated_at.isoformat(),
            ),
        )
    return job


def update_job(
    job_id: str,
    *,
    status: Optional[JobStatus | str] = None,
    phase: Optional[str] = None,
    progress: Optional[int] = None,
    message: Optional[str] = None,
    video_url: Optional[str] = None,
    error: Optional[str] = None,
) -> Optional[JobInfo]:
    with get_connection() as connection:
        row = connection.execute("SELECT * FROM jobs WHERE job_id = ?", (job_id,)).fetchone()
        if row is None:
            return None
        job = _row_to_job(row)
        if status is not None:
            job.status = status if isinstance(status, JobStatus) else _parse_job_status(str(status))
        if phase is not None:
            job.phase = phase.strip() or job.phase
        if progress is not None:
            job.progress = max(0, min(100, progress))
        if message is not None:
            job.message = message
        if video_url is not None:
            job.video_url = video_url
        if error is not None:
            job.error = error
        if job.status in {JobStatus.done, JobStatus.failed}:
            finished_at = _utcnow().isoformat()
            heartbeat_at = None
            lease_expires_at = None
            worker_id = None
        else:
            finished_at = None
            heartbeat_at = job.heartbeat_at.isoformat() if job.heartbeat_at else None
            lease_expires_at = job.lease_expires_at.isoformat() if job.lease_expires_at else None
            worker_id = job.worker_id
        job.updated_at = _utcnow()
        connection.execute(
            """
            UPDATE jobs
            SET status = ?, phase = ?, progress = ?, message = ?, video_url = ?, error = ?, worker_id = ?,
                heartbeat_at = ?, lease_expires_at = ?, updated_at = ?, finished_at = COALESCE(?, finished_at)
            WHERE job_id = ?
            """,
            (
                job.status.value,
                job.phase,
                job.progress,
                job.message,
                job.video_url,
                job.error,
                worker_id,
                heartbeat_at,
                lease_expires_at,
                job.updated_at.isoformat(),
                finished_at,
                job_id,
            ),
        )
        return job


def get_job(job_id: str) -> Optional[JobInfo]:
    with get_connection() as connection:
        row = connection.execute("SELECT * FROM jobs WHERE job_id = ?", (job_id,)).fetchone()
    return _row_to_job(row) if row is not None else None


def _extract_history_prompt(payload_text: str | None) -> str | None:
    text = _to_optional_text(payload_text)
    if not text:
        return None
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(payload, dict):
        return None

    prompt = _to_optional_text(payload.get("prompt"))
    if prompt:
        return prompt.strip()

    title = _to_optional_text(payload.get("title"))
    if title:
        return title.strip()

    script = payload.get("script")
    if isinstance(script, list):
        for item in script:
            candidate = _to_optional_text(item)
            if candidate:
                return candidate.strip()
    return None


def list_generation_history(limit: int = 20) -> list[GenerationHistoryRecord]:
    safe_limit = max(1, min(int(limit), 100))
    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT job_id, request_payload, video_url, created_at, updated_at
            FROM jobs
            WHERE status = ? AND video_url IS NOT NULL AND TRIM(video_url) <> ''
            ORDER BY created_at DESC
            LIMIT ?
            """,
            (JobStatus.done.value, safe_limit),
        ).fetchall()
    records: list[GenerationHistoryRecord] = []
    for row in rows:
        prompt = _extract_history_prompt(row["request_payload"]) or "未保存 prompt"
        video_url = _to_optional_text(row["video_url"])
        if not video_url:
            continue
        records.append(
            GenerationHistoryRecord(
                job_id=row["job_id"],
                prompt=prompt,
                video_url=video_url,
                created_at=datetime.fromisoformat(row["created_at"]),
                updated_at=datetime.fromisoformat(row["updated_at"]),
            )
        )
    return records


def requeue_running_jobs() -> int:
    now = _utcnow().isoformat()
    with get_connection() as connection:
        cursor = connection.execute(
            """
            UPDATE jobs
            SET status = ?, phase = ?, progress = 0, message = ?, error = NULL, worker_id = NULL, started_at = NULL,
                heartbeat_at = NULL, lease_expires_at = NULL, finished_at = NULL, updated_at = ?
            WHERE status = ?
            """,
            (
                JobStatus.queued.value,
                "queued",
                "Queued after worker restart",
                now,
                JobStatus.running.value,
            ),
        )
        return int(cursor.rowcount or 0)


def requeue_expired_jobs(now: datetime | None = None) -> int:
    reference = (now or _utcnow()).isoformat()
    with get_connection() as connection:
        cursor = connection.execute(
            """
            UPDATE jobs
            SET status = ?, phase = ?, progress = 0, message = ?, error = NULL, worker_id = NULL, started_at = NULL,
                heartbeat_at = NULL, lease_expires_at = NULL, updated_at = ?
            WHERE status = ? AND lease_expires_at IS NOT NULL AND lease_expires_at <= ?
            """,
            (
                JobStatus.queued.value,
                "queued",
                "Queued after worker lease expired",
                reference,
                JobStatus.running.value,
                reference,
            ),
        )
        return int(cursor.rowcount or 0)


def claim_next_queued_job(worker_id: str, lease_seconds: int = WORKER_LEASE_SECONDS) -> Optional[ClaimedJob]:
    now = _utcnow().isoformat()
    lease_expires_at = (_utcnow() + timedelta(seconds=max(lease_seconds, 1))).isoformat()
    with get_connection() as connection:
        connection.execute("BEGIN IMMEDIATE")
        row = connection.execute(
            """
            SELECT job_id, request_payload
            FROM jobs
            WHERE status = ?
            ORDER BY created_at ASC
            LIMIT 1
            """,
            (JobStatus.queued.value,),
        ).fetchone()
        if row is None:
            connection.execute("COMMIT")
            return None
        cursor = connection.execute(
            """
            UPDATE jobs
            SET status = ?, phase = ?, progress = ?, message = ?, error = NULL, worker_id = ?, started_at = ?,
                heartbeat_at = ?, lease_expires_at = ?, updated_at = ?
            WHERE job_id = ? AND status = ?
            """,
            (
                JobStatus.running.value,
                "starting",
                1,
                "Worker claimed job",
                worker_id,
                now,
                now,
                lease_expires_at,
                now,
                row["job_id"],
                JobStatus.queued.value,
            ),
        )
        if cursor.rowcount != 1:
            connection.execute("ROLLBACK")
            return None
        connection.execute("COMMIT")
        return ClaimedJob(job_id=row["job_id"], request_payload=row["request_payload"] or "")


def heartbeat_job(
    job_id: str,
    worker_id: str,
    *,
    lease_seconds: int = WORKER_LEASE_SECONDS,
    phase: Optional[str] = None,
    message: Optional[str] = None,
) -> bool:
    now = _utcnow()
    lease_expires_at = now + timedelta(seconds=max(lease_seconds, 1))
    with get_connection() as connection:
        cursor = connection.execute(
            """
            UPDATE jobs
            SET phase = COALESCE(?, phase), message = COALESCE(?, message), heartbeat_at = ?, lease_expires_at = ?, updated_at = ?
            WHERE job_id = ? AND status = ? AND worker_id = ?
            """,
            (
                phase.strip() if isinstance(phase, str) and phase.strip() else None,
                message,
                now.isoformat(),
                lease_expires_at.isoformat(),
                now.isoformat(),
                job_id,
                JobStatus.running.value,
                worker_id,
            ),
        )
        return cursor.rowcount == 1
