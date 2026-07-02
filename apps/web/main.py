from __future__ import annotations

import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from html import escape
from pathlib import Path
from typing import Any

from fastapi import Body, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles


REPO_ROOT = Path(__file__).resolve().parents[2]
APP_DIR = Path(__file__).resolve().parent
STATIC_DIR = APP_DIR / "static"
UPLOAD_DIR = APP_DIR / "uploads"
RESULT_DIR = APP_DIR / "results"
JOB_DIR = APP_DIR / "jobs"
SCRIPTS_DIR = REPO_ROOT / "work" / "scripts"
MAX_UPLOAD_BYTES = 6 * 1024 * 1024 * 1024
SHORT_VIDEO_LIMIT_SEC = 60.0
ALLOWED_SUFFIXES = {".mp4", ".mov", ".m4v", ".avi", ".mkv", ".webm"}
MAX_HISTORY_JOBS = 20
LONG_VIDEO_SCAN_THRESHOLD_SEC = 75.0

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


def run_step(job_id: str, title: str, progress: int, command: list[str], end_progress: int | None = None) -> None:
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
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write(f"\n\n## {title}\n")
        handle.write("$ " + " ".join(command) + "\n")
        handle.flush()
        proc = subprocess.Popen(
            command,
            cwd=str(REPO_ROOT),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=1,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            handle.write(line)
            if line.startswith("SPR_PROGRESS "):
                try:
                    payload = json.loads(line[len("SPR_PROGRESS "):])
                    ratio = max(0.0, min(1.0, float(payload.get("ratio", 0.0))))
                    mapped_progress = progress
                    if end_progress is not None:
                        mapped_progress = int(round(progress + (end_progress - progress) * ratio))
                    frame = payload.get("frame")
                    total = payload.get("total")
                    detail = f"{title} {frame}/{total} 帧" if frame and total else title
                    patch_job(job_id, status="processing", stage=detail, progress=mapped_progress)
                except Exception:
                    pass
        proc.wait()
        handle.write(f"\n## {title} finished ({time.time() - started:.1f}s)\n")
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


def write_long_pose_report(
    report: Path,
    *,
    title: str,
    input_url: str,
    overlay_url: str,
    duration_sec: float | None,
    log_url: str,
) -> None:
    duration_text = f"{duration_sec / 60:.1f} 分钟" if duration_sec and duration_sec > 0 else "长视频"
    html = f"""<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>{escape(title)} - 骨架渲染</title>
    <style>
      :root {{ color-scheme: dark; --bg:#06120f; --panel:#0d1917; --line:rgba(163,244,200,.22); --text:#f2fff7; --muted:#a9c7bb; --mint:#93f2c1; --yellow:#f7d76e; }}
      * {{ box-sizing:border-box; }}
      body {{ margin:0; min-height:100vh; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; color:var(--text); background:linear-gradient(90deg,rgba(9,98,60,.20),transparent 32%,rgba(247,215,110,.10)),var(--bg); }}
      main {{ width:min(1280px,calc(100vw - 28px)); margin:0 auto; padding:20px 0; display:grid; gap:14px; }}
      header, section {{ border:1px solid var(--line); border-radius:8px; background:rgba(13,25,23,.94); }}
      header {{ padding:16px; display:flex; justify-content:space-between; align-items:center; gap:12px; }}
      h1, h2, p {{ margin:0; }}
      h1 {{ font-size:24px; }}
      p {{ color:var(--muted); line-height:1.6; }}
      .chip {{ border:1px solid rgba(247,215,110,.35); color:#fff8c4; border-radius:999px; padding:7px 10px; font-size:12px; white-space:nowrap; }}
      .grid {{ display:grid; grid-template-columns:1fr 1fr; gap:14px; }}
      section {{ padding:12px; display:grid; gap:10px; min-width:0; }}
      video {{ width:100%; border:1px solid rgba(215,248,223,.18); border-radius:8px; background:#020806; }}
      .actions {{ display:flex; flex-wrap:wrap; gap:10px; }}
      a {{ min-height:40px; display:inline-grid; place-items:center; border:1px solid var(--line); border-radius:8px; padding:0 14px; color:var(--text); background:#081410; text-decoration:none; font-weight:800; }}
      @media (max-width: 900px) {{ .grid {{ grid-template-columns:1fr; }} header {{ align-items:flex-start; flex-direction:column; }} }}
    </style>
  </head>
  <body>
    <main>
      <header>
        <div>
          <h1>长视频骨架渲染</h1>
          <p>{escape(title)} · {escape(duration_text)} · 仅生成全程骨架标注，不做重发力评分和动作证据分析。</p>
        </div>
        <span class="chip">长视频模式</span>
      </header>
      <div class="grid">
        <section>
          <h2>骨架标注视频</h2>
          <video src="{overlay_url}" controls playsinline preload="metadata"></video>
          <div class="actions"><a href="{overlay_url}" download>下载骨架视频</a><a href="{log_url}" target="_blank" rel="noreferrer">处理日志</a></div>
        </section>
        <section>
          <h2>原始预处理视频</h2>
          <video src="{input_url}" controls playsinline preload="metadata"></video>
          <div class="actions"><a href="{input_url}" download>下载预处理视频</a></div>
        </section>
      </div>
    </main>
  </body>
</html>
"""
    report.write_text(html, encoding="utf-8")


def preprocess_video(job: dict[str, Any], source: Path | None = None) -> Path:
    source = source or Path(job["upload_path"])
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


def probe_duration_sec(source: Path) -> float:
    proc = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=nokey=1:noprint_wrappers=1",
            str(source),
        ],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        return 0.0
    try:
        return float(proc.stdout.strip() or 0.0)
    except ValueError:
        return 0.0


def prepare_review_input(job: dict[str, Any]) -> Path:
    source = Path(job["upload_path"])
    if os.getenv("SPR_AUTO_CUT", "0") != "1":
        return preprocess_video(job, source)

    source_duration = probe_duration_sec(source)
    if source_duration < LONG_VIDEO_SCAN_THRESHOLD_SEC:
        return preprocess_video(job, source)

    output_dir = RESULT_DIR / job["id"] / "input"
    output_dir.mkdir(parents=True, exist_ok=True)
    label = f"job_{job['id'][:8]}"
    run_step(
        job["id"],
        "长视频快速扫描与自动裁切",
        18,
        [
            sys.executable,
            str(SCRIPTS_DIR / "detect_rally_segments.py"),
            "--video",
            str(source),
            "--output-dir",
            str(output_dir),
            "--label",
            label,
        ],
    )
    summary_path = output_dir / f"{label}_rally_segments.json"
    if not summary_path.exists():
        return preprocess_video(job, source)
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    clipped = Path(summary.get("outputs", {}).get("clipped_video", ""))
    if not clipped.exists():
        return preprocess_video(job, source)
    patch_job(
        job["id"],
        clip_summary={
            "source_duration_sec": summary.get("source_duration_sec"),
            "active_duration_sec": summary.get("active_duration_sec"),
            "segment_count": summary.get("segment_count"),
            "scan_time_sec": summary.get("scan_time_sec"),
            "render_time_sec": summary.get("render_time_sec"),
            "reduction_ratio": summary.get("reduction_ratio"),
            "segments": summary.get("segments", []),
            "summary_url": f"/results/{job['id']}/input/{summary_path.name}",
            "clipped_video_url": f"/results/{job['id']}/input/{clipped.name}",
        },
    )
    return clipped


def process_job(job_id: str) -> None:
    try:
        job = patch_job(job_id, status="processing", stage="准备分析", progress=8)
        job_mode = job.get("mode", "short")
        job_dir = RESULT_DIR / job_id
        pose_dir = job_dir / "pose"
        review_dir = job_dir / "review"
        pose_dir.mkdir(parents=True, exist_ok=True)
        review_dir.mkdir(parents=True, exist_ok=True)

        video_path = prepare_review_input(job) if job_mode == "short" else preprocess_video(job)
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
            end_progress=94 if job_mode == "long" else 76,
        )

        landmarks = pose_dir / f"{label}_holistic_landmarks.json"
        metrics = pose_dir / f"{label}_holistic_metrics.json"
        pose_overlay = pose_dir / f"{label}_holistic_overlay.mp4"
        input_url = f"/results/{job_id}/input/{video_path.name}"
        pose_overlay_url = f"/results/{job_id}/pose/{pose_overlay.name}"
        if job_mode == "long":
            report = review_dir / f"{label}_long_pose_review.html"
            write_long_pose_report(
                report,
                title=job.get("filename") or label,
                input_url=input_url,
                overlay_url=pose_overlay_url,
                duration_sec=job.get("duration_sec"),
                log_url=f"/api/jobs/{job_id}/log",
            )
            patch_job(
                job_id,
                status="completed",
                stage="完成",
                progress=100,
                completed_at=now_iso(),
                result={
                    "input_url": input_url,
                    "report_url": f"/results/{job_id}/review/{report.name}",
                    "overlay_url": pose_overlay_url,
                    "pose_overlay_url": pose_overlay_url,
                    "metrics_url": f"/results/{job_id}/pose/{metrics.name}",
                    "log_url": f"/api/jobs/{job_id}/log",
                },
            )
            prune_history_jobs()
            return

        run_step(
            job_id,
            "动作复盘生成",
            80,
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
                "--handedness",
                os.getenv("SPR_HANDEDNESS", "auto"),
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
        prune_history_jobs()
    except Exception as exc:
        patch_job(
            job_id,
            status="failed",
            stage="失败",
            error=str(exc),
            progress=100,
            result={"log_url": f"/api/jobs/{job_id}/log"},
        )
        prune_history_jobs()


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
async def create_job(video: UploadFile = File(...), mode: str = Form("short")) -> dict[str, Any]:
    suffix = valid_suffix(video.filename or "")
    mode = (mode or "short").strip().lower()
    if mode not in {"short", "long"}:
        raise HTTPException(status_code=400, detail="unsupported review mode")

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

    duration_sec = probe_duration_sec(upload_path)
    if mode == "short" and duration_sec > SHORT_VIDEO_LIMIT_SEC + 0.5:
        upload_path.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail="短视频复盘模式最多支持 60 秒，请切换到长视频骨架模式。")

    job = {
        "id": job_id,
        "filename": video.filename,
        "upload_path": str(upload_path),
        "size_bytes": size,
        "duration_sec": round(duration_sec, 3) if duration_sec else None,
        "mode": mode,
        "status": "queued",
        "stage": "排队中",
        "progress": 3,
        "created_at": now_iso(),
        "updated_at": now_iso(),
        "result": None,
        "clip_summary": None,
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
        if len(items) >= max(1, min(limit, MAX_HISTORY_JOBS)):
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


@app.post("/api/jobs/{job_id}/clips")
def create_review_clip(job_id: str, payload: dict[str, Any] = Body(...)) -> dict[str, Any]:
    job = read_job(job_id)
    if job.get("status") != "completed":
        raise HTTPException(status_code=409, detail="job is not completed")
    result = job.get("result") or {}
    overlay_url = result.get("overlay_url")
    if not overlay_url:
        raise HTTPException(status_code=404, detail="overlay video not available")

    start_sec = float(payload.get("start_sec", 0.0))
    end_sec = float(payload.get("end_sec", 0.0))
    if not (0 <= start_sec < end_sec):
        raise HTTPException(status_code=400, detail="invalid clip range")
    if end_sec - start_sec > 600:
        raise HTTPException(status_code=400, detail="clip is too long")

    overlay = RESULT_DIR / job_id / "review" / Path(overlay_url).name
    if not overlay.exists():
        raise HTTPException(status_code=404, detail="overlay video file not found")

    clips_dir = RESULT_DIR / job_id / "clips"
    clips_dir.mkdir(parents=True, exist_ok=True)
    clip_name = f"clip_{int(start_sec * 1000):08d}_{int(end_sec * 1000):08d}.mp4"
    clip_path = clips_dir / clip_name
    command = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-ss",
        f"{start_sec:.3f}",
        "-to",
        f"{end_sec:.3f}",
        "-i",
        str(overlay),
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "20",
        "-pix_fmt",
        "yuv420p",
        "-movflags",
        "+faststart",
        str(clip_path),
    ]
    proc = subprocess.run(command, cwd=str(REPO_ROOT), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode != 0:
        raise HTTPException(status_code=500, detail=proc.stdout or "failed to create clip")
    return {
        "clip_url": f"/results/{job_id}/clips/{clip_name}",
        "start_sec": round(start_sec, 3),
        "end_sec": round(end_sec, 3),
        "duration_sec": round(end_sec - start_sec, 3),
    }


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
        "duration_sec": job.get("duration_sec"),
        "mode": job.get("mode", "short"),
        "status": job.get("status"),
        "stage": job.get("stage"),
        "progress": job.get("progress", 0),
        "created_at": job.get("created_at"),
        "updated_at": job.get("updated_at"),
        "completed_at": job.get("completed_at"),
        "result": job.get("result"),
        "clip_summary": job.get("clip_summary"),
        "error": job.get("error"),
    }


def prune_history_jobs() -> None:
    finished: list[dict[str, Any]] = []
    for path in JOB_DIR.glob("*.json"):
        try:
            job = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if job.get("status") in {"completed", "failed"}:
            finished.append(job)
    finished.sort(key=lambda item: item.get("completed_at") or item.get("updated_at") or item.get("created_at") or "", reverse=True)
    for job in finished[MAX_HISTORY_JOBS:]:
        job_id = job.get("id")
        if not job_id:
            continue
        try:
            upload_path = job.get("upload_path")
            if upload_path:
                Path(upload_path).unlink(missing_ok=True)
            shutil.rmtree(RESULT_DIR / job_id, ignore_errors=True)
            job_path(job_id).unlink(missing_ok=True)
        except Exception:
            continue
