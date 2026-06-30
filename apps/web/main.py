from __future__ import annotations

import json
import queue
import shutil
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles


REPO_ROOT = Path(__file__).resolve().parents[2]
APP_DIR = Path(__file__).resolve().parent
STATIC_DIR = APP_DIR / "static"
UPLOAD_DIR = APP_DIR / "uploads"
RESULT_DIR = APP_DIR / "results"
JOB_DIR = APP_DIR / "jobs"
SCRIPTS_DIR = REPO_ROOT / "work" / "scripts"

MAX_UPLOAD_BYTES = 900 * 1024 * 1024
ALLOWED_SUFFIXES = {".mp4", ".mov", ".m4v", ".avi", ".mkv", ".webm"}

for folder in (UPLOAD_DIR, RESULT_DIR, JOB_DIR):
    folder.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="ShuttlePoseReview Web MVP")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
app.mount("/results", StaticFiles(directory=RESULT_DIR), name="results")

job_queue: queue.Queue[str] = queue.Queue()
jobs_lock = threading.Lock()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def job_path(job_id: str) -> Path:
    return JOB_DIR / f"{job_id}.json"


def read_job(job_id: str) -> dict[str, Any]:
    path = job_path(job_id)
    if not path.exists():
        raise HTTPException(status_code=404, detail="job not found")
    return json.loads(path.read_text(encoding="utf-8"))


def write_job(job: dict[str, Any]) -> None:
    path = job_path(job["id"])
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def patch_job(job_id: str, **updates: Any) -> dict[str, Any]:
    with jobs_lock:
        job = read_job(job_id)
        job.update(updates)
        job["updated_at"] = now_iso()
        write_job(job)
        return job


def valid_suffix(filename: str) -> str:
    suffix = Path(filename).suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(status_code=400, detail="unsupported video type")
    return suffix


def run_step(job_id: str, title: str, progress: int, command: list[str]) -> None:
    patch_job(job_id, status="processing", stage=title, progress=progress)
    started = time.time()
    log_path = RESULT_DIR / job_id / "pipeline.log"
    executable = command[0]
    executable_path = Path(executable)
    if executable_path.parent == Path("."):
        executable_exists = shutil.which(executable) is not None
    else:
        executable_exists = executable_path.exists()
    if not executable_exists:
        hint = dependency_hint(executable)
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(f"\n\n## {title} (0.0s)\n")
            handle.write("$ " + " ".join(command) + "\n")
            handle.write(f"Missing executable: {executable}\n")
            if hint:
                handle.write(hint + "\n")
        raise RuntimeError(hint or f"找不到可执行文件：{executable}")
    proc = subprocess.run(
        command,
        cwd=str(REPO_ROOT),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write(f"\n\n## {title} ({time.time() - started:.1f}s)\n")
        handle.write("$ " + " ".join(command) + "\n")
        handle.write(proc.stdout or "")
    if proc.returncode != 0:
        raise RuntimeError(f"{title} failed; see {log_path}")


def dependency_hint(executable: str) -> str | None:
    name = Path(executable).name.lower()
    if name in {"ffmpeg", "ffmpeg.exe"}:
        return (
            "找不到 ffmpeg。请先安装 FFmpeg，并确认在终端执行 `ffmpeg -version` "
            "可以正常输出版本信息；Windows 用户需要把 FFmpeg 的 bin 目录加入 PATH。"
        )
    if name.startswith("python"):
        return (
            "找不到 Python 解释器。请确认使用已安装依赖的 Python/venv 启动 Web 服务。"
        )
    return None


def open_report_on_review_screen(report: Path) -> None:
    if not report.exists():
        return
    html = report.read_text(encoding="utf-8")
    html = html.replace(
        '<section id="landing" class="landing">',
        '<section id="landing" class="landing" hidden>',
        1,
    )
    html = html.replace(
        '<div id="reviewApp" class="app" hidden>',
        '<div id="reviewApp" class="app">',
        1,
    )
    report.write_text(html, encoding="utf-8")


def preprocess_video(job: dict[str, Any]) -> Path:
    source = Path(job["upload_path"])
    output_dir = RESULT_DIR / job["id"] / "input"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / f"{job['id']}_input_720.mp4"
    command = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(source),
        "-vf",
        "scale='min(1280,iw)':-2,fps=30",
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "23",
        "-pix_fmt",
        "yuv420p",
        "-movflags",
        "+faststart",
        str(output),
    ]
    run_step(job["id"], "视频预处理", 18, command)
    return output


def process_job(job_id: str) -> None:
    try:
        job = patch_job(job_id, status="processing", stage="准备分析", progress=8)
        job_dir = RESULT_DIR / job_id
        pose_dir = job_dir / "pose"
        review_dir = job_dir / "review"
        pose_dir.mkdir(parents=True, exist_ok=True)
        review_dir.mkdir(parents=True, exist_ok=True)

        video_path = preprocess_video(job)
        label = f"job_{job_id[:8]}"

        run_step(
            job_id,
            "骨架提取",
            42,
            [
                sys.executable,
                str(SCRIPTS_DIR / "mediapipe_holistic_test.py"),
                "--video",
                str(video_path),
                "--output-dir",
                str(pose_dir),
                "--label",
                label,
                "--model-complexity",
                "1",
            ],
        )

        landmarks = pose_dir / f"{label}_holistic_landmarks.json"
        metrics = pose_dir / f"{label}_holistic_metrics.json"
        run_step(
            job_id,
            "动作复盘生成",
            76,
            [
                sys.executable,
                str(SCRIPTS_DIR / "build_2d_action_review.py"),
                "--landmarks",
                str(landmarks),
                "--metrics",
                str(metrics),
                "--video",
                str(video_path),
                "--output-dir",
                str(review_dir),
                "--label",
                label,
            ],
        )

        report = review_dir / f"{label}_2d_action_review.html"
        result_json = review_dir / f"{label}_2d_action_review.json"
        overlay = review_dir / f"{label}_2d_review_overlay.mp4"
        open_report_on_review_screen(report)
        patch_job(
            job_id,
            status="completed",
            stage="完成",
            progress=100,
            completed_at=now_iso(),
            result={
                "input_url": f"/results/{job_id}/input/{video_path.name}",
                "report_url": f"/results/{job_id}/review/{report.name}",
                "json_url": f"/results/{job_id}/review/{result_json.name}",
                "overlay_url": f"/results/{job_id}/review/{overlay.name}",
                "pose_overlay_url": f"/results/{job_id}/pose/{label}_holistic_overlay.mp4",
                "log_url": f"/api/jobs/{job_id}/log",
            },
        )
    except Exception as exc:
        patch_job(
            job_id,
            status="failed",
            stage="失败",
            error=str(exc),
            progress=100,
            result={"log_url": f"/api/jobs/{job_id}/log"},
        )


def worker_loop() -> None:
    while True:
        job_id = job_queue.get()
        try:
            process_job(job_id)
        finally:
            job_queue.task_done()


@app.on_event("startup")
def start_worker() -> None:
    thread = threading.Thread(target=worker_loop, daemon=True)
    thread.start()


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.post("/api/jobs")
async def create_job(video: UploadFile = File(...)) -> dict[str, Any]:
    suffix = valid_suffix(video.filename or "")

    job_id = uuid.uuid4().hex
    upload_path = UPLOAD_DIR / f"{job_id}{suffix}"
    size = 0
    with upload_path.open("wb") as handle:
        while True:
            chunk = await video.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if size > MAX_UPLOAD_BYTES:
                handle.close()
                upload_path.unlink(missing_ok=True)
                raise HTTPException(status_code=413, detail="video is too large")
            handle.write(chunk)

    job = {
        "id": job_id,
        "filename": video.filename,
        "upload_path": str(upload_path),
        "size_bytes": size,
        "status": "queued",
        "stage": "排队中",
        "progress": 3,
        "created_at": now_iso(),
        "updated_at": now_iso(),
        "result": None,
        "error": None,
    }
    write_job(job)
    job_queue.put(job_id)
    return public_job(job)


@app.get("/api/jobs")
def list_jobs(limit: int = 8) -> dict[str, Any]:
    items = []
    for path in sorted(JOB_DIR.glob("*.json"), key=lambda item: item.stat().st_mtime, reverse=True):
        try:
            items.append(public_job(json.loads(path.read_text(encoding="utf-8"))))
        except Exception:
            continue
        if len(items) >= max(1, min(limit, 24)):
            break
    return {"jobs": items}


@app.get("/api/jobs/{job_id}")
def get_job(job_id: str) -> dict[str, Any]:
    return public_job(read_job(job_id))


@app.get("/api/jobs/{job_id}/log")
def get_log(job_id: str) -> FileResponse:
    read_job(job_id)
    log_path = RESULT_DIR / job_id / "pipeline.log"
    if not log_path.exists():
        raise HTTPException(status_code=404, detail="log not available yet")
    return FileResponse(log_path, media_type="text/plain")


@app.delete("/api/jobs/{job_id}")
def delete_job(job_id: str) -> dict[str, str]:
    job = read_job(job_id)
    Path(job["upload_path"]).unlink(missing_ok=True)
    shutil.rmtree(RESULT_DIR / job_id, ignore_errors=True)
    job_path(job_id).unlink(missing_ok=True)
    return {"status": "deleted"}


def public_job(job: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": job["id"],
        "filename": job.get("filename"),
        "size_bytes": job.get("size_bytes"),
        "status": job.get("status"),
        "stage": job.get("stage"),
        "progress": job.get("progress", 0),
        "created_at": job.get("created_at"),
        "updated_at": job.get("updated_at"),
        "completed_at": job.get("completed_at"),
        "result": job.get("result"),
        "error": job.get("error"),
    }
