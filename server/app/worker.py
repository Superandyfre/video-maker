from __future__ import annotations

import asyncio
import logging
import os
import socket
import time

from app.cleanup import run_cleanup
from app.config import (
    JOB_POLL_INTERVAL_SECONDS,
    JOB_TIMEOUT_SECONDS,
    OUTPUT_RETENTION_HOURS,
    TEMP_RETENTION_HOURS,
    UPLOAD_RETENTION_HOURS,
    WORKER_HEARTBEAT_INTERVAL_SECONDS,
    WORKER_LEASE_SECONDS,
)
from app.jobs import claim_next_queued_job, heartbeat_job, requeue_expired_jobs, requeue_running_jobs, update_job
from app.renderer import WorkerOwnershipLostError, render_video
from app.schemas import CreateJobRequest
from app.state_db import initialize_database
from app.storage import ensure_directories

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger(__name__)


def _worker_id() -> str:
    return f"{socket.gethostname()}-{os.getpid()}"


async def _run_claimed_job(job_id: str, payload: str, worker_id: str) -> None:
    try:
        request = CreateJobRequest.model_validate_json(payload)
    except Exception as exc:
        logger.exception("Invalid queued job payload for job %s", job_id)
        update_job(
            job_id,
            status="failed",
            phase="failed",
            progress=0,
            message="Failed",
            error=f"Invalid job payload: {exc}",
            expected_worker_id=worker_id,
        )
        return
    stop_event = asyncio.Event()

    async def heartbeat_loop() -> None:
        while not stop_event.is_set():
            heartbeat_job(
                job_id,
                worker_id,
                lease_seconds=WORKER_LEASE_SECONDS,
            )
            try:
                await asyncio.wait_for(stop_event.wait(), timeout=WORKER_HEARTBEAT_INTERVAL_SECONDS)
            except asyncio.TimeoutError:
                continue

    heartbeat_task = asyncio.create_task(heartbeat_loop())
    try:
        try:
            await asyncio.wait_for(render_video(job_id, request, worker_id=worker_id), timeout=JOB_TIMEOUT_SECONDS)
        except WorkerOwnershipLostError:
            logger.warning("Worker %s stopped job %s after losing ownership", worker_id, job_id)
        except asyncio.TimeoutError:
            logger.error("Job %s exceeded %ss timeout, aborting", job_id, JOB_TIMEOUT_SECONDS)
            update_job(
                job_id,
                status="failed",
                phase="timeout",
                progress=0,
                message="Job exceeded time limit",
                error=f"Worker aborted job after {JOB_TIMEOUT_SECONDS}s without completion",
                expected_worker_id=worker_id,
            )
    finally:
        stop_event.set()
        heartbeat_task.cancel()
        try:
            await heartbeat_task
        except asyncio.CancelledError:
            pass


def run_worker_loop() -> None:
    ensure_directories()
    initialize_database()
    run_cleanup(
        temp_retention_hours=TEMP_RETENTION_HOURS,
        upload_retention_hours=UPLOAD_RETENTION_HOURS,
        output_retention_hours=OUTPUT_RETENTION_HOURS,
    )
    recovered = requeue_running_jobs()
    if recovered:
        logger.info("Re-queued %s interrupted jobs after worker startup", recovered)

    worker_id = _worker_id()
    logger.info("Video maker worker started with id %s", worker_id)
    while True:
        reclaimed = requeue_expired_jobs()
        if reclaimed:
            logger.warning("Re-queued %s jobs with expired worker leases", reclaimed)
        claimed = claim_next_queued_job(worker_id, lease_seconds=WORKER_LEASE_SECONDS)
        if claimed is None:
            time.sleep(JOB_POLL_INTERVAL_SECONDS)
            continue
        logger.info("Worker %s claimed job %s", worker_id, claimed.job_id)
        try:
            asyncio.run(_run_claimed_job(claimed.job_id, claimed.request_payload, worker_id))
        except Exception:
            logger.exception("Worker execution crashed for job %s", claimed.job_id)


if __name__ == "__main__":
    run_worker_loop()
