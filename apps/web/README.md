# ShuttlePoseReview Web MVP

This app turns the current static prototype into a real local upload workflow:

1. Upload a badminton video.
2. Create a queued analysis job.
3. Preprocess the video with FFmpeg.
4. Run MediaPipe Holistic pose extraction.
5. Build the 2D action review report.
6. Open the generated report, overlay video, and JSON result.

The browser stays in one clean workflow: upload screen, processing screen, then an embedded review workspace with the full report, annotated video, JSON export, and logs.

## Run

Use the existing Good-Badminton virtualenv because it already contains OpenCV and MediaPipe:

```bash
work/Good-Badminton/.venv/bin/pip install -r apps/web/requirements.txt
work/Good-Badminton/.venv/bin/python -m uvicorn apps.web.main:app --host 127.0.0.1 --port 8787
```

Then open:

```text
http://127.0.0.1:8787
```

If that port is already occupied, use another one:

```bash
work/Good-Badminton/.venv/bin/python -m uvicorn apps.web.main:app --host 127.0.0.1 --port 8788
```

## Notes

- Jobs are stored under `apps/web/jobs`.
- Uploaded videos are stored under `apps/web/uploads`.
- Generated results are stored under `apps/web/results`.
- The preprocessing step requires a working `ffmpeg` command. If Windows reports `[WinError 2] 系统找不到指定的文件`, install FFmpeg and add its `bin` directory to `PATH`, then verify:

```powershell
ffmpeg -version
```

- The first implementation uses an in-process background queue for simple local demos. For production, replace it with Redis/RQ, Celery, or a cloud job runner.
- The current analysis is pose-only and does not claim true shuttle speed, true 3D reconstruction, or medical-grade biomechanical diagnosis.
