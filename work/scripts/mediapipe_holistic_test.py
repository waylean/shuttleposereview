import argparse
import json
import subprocess
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np


POSE_CONNECTIONS = list(mp.solutions.pose.POSE_CONNECTIONS)
HAND_CONNECTIONS = list(mp.solutions.hands.HAND_CONNECTIONS)


def lm_to_px(landmark, width, height):
    return int(round(landmark.x * width)), int(round(landmark.y * height))


def draw_pose(frame, landmarks, color=(80, 230, 255)):
    if landmarks is None:
        return
    h, w = frame.shape[:2]
    for a, b in POSE_CONNECTIONS:
        pa = landmarks.landmark[a]
        pb = landmarks.landmark[b]
        if getattr(pa, "visibility", 1.0) < 0.35 or getattr(pb, "visibility", 1.0) < 0.35:
            continue
        cv2.line(frame, lm_to_px(pa, w, h), lm_to_px(pb, w, h), color, 4, cv2.LINE_AA)
    for idx, lm in enumerate(landmarks.landmark):
        if getattr(lm, "visibility", 1.0) < 0.35:
            continue
        cv2.circle(frame, lm_to_px(lm, w, h), 4, (255, 80, 210), -1, cv2.LINE_AA)


def draw_hand(frame, landmarks, color):
    if landmarks is None:
        return
    h, w = frame.shape[:2]
    for a, b in HAND_CONNECTIONS:
        cv2.line(frame, lm_to_px(landmarks.landmark[a], w, h), lm_to_px(landmarks.landmark[b], w, h), color, 3, cv2.LINE_AA)
    for lm in landmarks.landmark:
        cv2.circle(frame, lm_to_px(lm, w, h), 3, (245, 245, 245), -1, cv2.LINE_AA)


def landmarks_to_list(landmarks, width=None, height=None, with_visibility=False):
    if landmarks is None:
        return None
    out = []
    for lm in landmarks.landmark:
        item = {"x": float(lm.x), "y": float(lm.y), "z": float(lm.z)}
        if width is not None and height is not None:
            item["px"] = float(lm.x * width)
            item["py"] = float(lm.y * height)
        if with_visibility:
            item["visibility"] = float(getattr(lm, "visibility", 1.0))
        out.append(item)
    return out


def build_html(frames, metrics, out_path):
    payload = {"frames": frames, "metrics": metrics, "pose_connections": POSE_CONNECTIONS, "hand_connections": HAND_CONNECTIONS}
    html = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>MediaPipe Holistic 3D Viewer</title>
  <style>
    body { margin: 0; background: #10141b; color: #eef2f8; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    main { display: grid; grid-template-columns: 1fr 330px; min-height: 100vh; }
    canvas { width: 100%; height: 100vh; display: block; background: radial-gradient(circle at center, #1f2937, #0b0f15); }
    aside { padding: 20px; background: #151b25; border-left: 1px solid #2c3444; }
    h1 { font-size: 20px; margin: 0 0 12px; }
    p { line-height: 1.55; color: #cbd5e1; }
    input { width: 100%; }
    .metric { display: grid; grid-template-columns: 1fr auto; gap: 8px; border-bottom: 1px solid #2c3444; padding: 8px 0; }
    .small { font-size: 12px; color: #9aa7bd; }
  </style>
</head>
<body>
<main>
<canvas id="canvas" width="1200" height="800"></canvas>
<aside>
  <h1>MediaPipe Holistic 3D</h1>
  <p class="small">使用 MediaPipe pose world landmarks + hand landmarks 的估计视图。手部深度来自单目估计和归一化坐标，不等同于真实 3D 重建。</p>
  <input id="slider" type="range" min="0" max="0" value="0">
  <div class="metric"><span>Frame</span><strong id="frameText">0</strong></div>
  <div class="metric"><span>Pose 覆盖率</span><strong id="poseCov"></strong></div>
  <div class="metric"><span>左手覆盖率</span><strong id="lhCov"></strong></div>
  <div class="metric"><span>右手覆盖率</span><strong id="rhCov"></strong></div>
  <div class="metric"><span>处理速度</span><strong id="speedText"></strong></div>
</aside>
</main>
<script id="data" type="application/json">__DATA__</script>
<script>
const data = JSON.parse(document.getElementById('data').textContent);
const canvas = document.getElementById('canvas'), ctx = canvas.getContext('2d');
const slider = document.getElementById('slider');
slider.max = Math.max(0, data.frames.length - 1);
document.getElementById('poseCov').textContent = (data.metrics.pose_coverage * 100).toFixed(1) + '%';
document.getElementById('lhCov').textContent = (data.metrics.left_hand_coverage * 100).toFixed(1) + '%';
document.getElementById('rhCov').textContent = (data.metrics.right_hand_coverage * 100).toFixed(1) + '%';
document.getElementById('speedText').textContent = data.metrics.processing_speed_ratio.toFixed(2) + 'x';
let rx = -0.15, ry = 0.35, drag = false, lx = 0, ly = 0;
canvas.addEventListener('mousedown', e => { drag=true; lx=e.clientX; ly=e.clientY; });
window.addEventListener('mouseup', () => drag=false);
window.addEventListener('mousemove', e => { if(!drag) return; ry += (e.clientX-lx)*0.008; rx += (e.clientY-ly)*0.008; lx=e.clientX; ly=e.clientY; draw(); });
slider.addEventListener('input', draw);
function rot(p) {
  let [x,y,z] = p; let cy=Math.cos(ry), sy=Math.sin(ry), cx=Math.cos(rx), sx=Math.sin(rx);
  let x1=x*cy+z*sy, z1=-x*sy+z*cy, y1=y*cx-z1*sx, z2=y*sx+z1*cx;
  return [x1,y1,z2];
}
function project(p) { const r=rot(p); const s=310/(2.8+r[2]); return [canvas.width/2+r[0]*s, canvas.height/2-r[1]*s]; }
function drawLine(a,b,color,width=6) { const pa=project(a), pb=project(b); ctx.strokeStyle=color; ctx.lineWidth=width; ctx.lineCap='round'; ctx.beginPath(); ctx.moveTo(pa[0],pa[1]); ctx.lineTo(pb[0],pb[1]); ctx.stroke(); }
function drawPoint(p,color,r=5) { const pp=project(p); ctx.fillStyle=color; ctx.beginPath(); ctx.arc(pp[0],pp[1],r,0,Math.PI*2); ctx.fill(); }
function posePoint(lm) { return [lm.x, -lm.y, lm.z]; }
function handPoint(lm, wrist) { return [wrist[0] + (lm.x - 0.5) * 0.8, wrist[1] - (lm.y - 0.5) * 0.8, wrist[2] + lm.z * 0.8]; }
function draw() {
  const idx=Number(slider.value); document.getElementById('frameText').textContent = idx + ' / ' + (data.frames.length-1);
  ctx.clearRect(0,0,canvas.width,canvas.height); const f=data.frames[idx];
  if (f.pose_world) {
    for (const [a,b] of data.pose_connections) {
      const A=f.pose_world[a], B=f.pose_world[b]; if(!A||!B) continue;
      drawLine(posePoint(A), posePoint(B), '#6ee7ff', 7);
    }
    for (const lm of f.pose_world) if(lm) drawPoint(posePoint(lm), '#ff5ad8', 5);
    const lw = posePoint(f.pose_world[15]), rw = posePoint(f.pose_world[16]);
    if (f.left_hand) for (const [a,b] of data.hand_connections) drawLine(handPoint(f.left_hand[a], lw), handPoint(f.left_hand[b], lw), '#ffd166', 4);
    if (f.right_hand) for (const [a,b] of data.hand_connections) drawLine(handPoint(f.right_hand[a], rw), handPoint(f.right_hand[b], rw), '#a7f3d0', 4);
  }
}
draw();
</script>
</body>
</html>
"""
    out_path.write_text(html.replace("__DATA__", json.dumps(payload)), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--label", default="mediapipe")
    parser.add_argument("--model-complexity", type=int, default=2)
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        raise RuntimeError(args.video)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frames_total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    duration = frames_total / fps if fps else 0

    temp_video = out_dir / f"{args.label}_holistic.temp.mp4"
    output_video = out_dir / f"{args.label}_holistic_overlay.mp4"
    writer = cv2.VideoWriter(str(temp_video), cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height))

    frame_records = []
    pose_count = left_hand_count = right_hand_count = 0

    import time
    started = time.time()
    with mp.solutions.holistic.Holistic(
        static_image_mode=False,
        model_complexity=args.model_complexity,
        smooth_landmarks=True,
        enable_segmentation=False,
        refine_face_landmarks=False,
        min_detection_confidence=0.45,
        min_tracking_confidence=0.45,
    ) as holistic:
        frame_idx = 0
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            rgb.flags.writeable = False
            res = holistic.process(rgb)

            pose_ok = res.pose_landmarks is not None
            lh_ok = res.left_hand_landmarks is not None
            rh_ok = res.right_hand_landmarks is not None
            pose_count += int(pose_ok)
            left_hand_count += int(lh_ok)
            right_hand_count += int(rh_ok)

            draw_pose(frame, res.pose_landmarks)
            draw_hand(frame, res.left_hand_landmarks, (0, 220, 255))
            draw_hand(frame, res.right_hand_landmarks, (120, 255, 170))
            cv2.rectangle(frame, (0, 0), (width, 44), (14, 18, 26), -1)
            cv2.putText(
                frame,
                f"{args.label} | holistic | pose={pose_ok} left_hand={lh_ok} right_hand={rh_ok} | frame {frame_idx}/{frames_total}",
                (18, 30),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.62,
                (120, 255, 170),
                2,
                cv2.LINE_AA,
            )
            writer.write(frame)

            frame_records.append(
                {
                    "frame": frame_idx,
                    "time_sec": frame_idx / fps if fps else None,
                    "pose": landmarks_to_list(res.pose_landmarks, width, height, True),
                    "pose_world": landmarks_to_list(res.pose_world_landmarks, None, None, True),
                    "left_hand": landmarks_to_list(res.left_hand_landmarks, width, height, False),
                    "right_hand": landmarks_to_list(res.right_hand_landmarks, width, height, False),
                }
            )
            frame_idx += 1

    cap.release()
    writer.release()
    elapsed = time.time() - started
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(temp_video),
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
            str(output_video),
        ],
        check=True,
    )
    temp_video.unlink(missing_ok=True)

    n = len(frame_records)
    metrics = {
        "label": args.label,
        "video": args.video,
        "frames": n,
        "fps": fps,
        "duration_sec": duration,
        "processing_time_sec": elapsed,
        "processing_speed_ratio": elapsed / duration if duration else None,
        "pose_frames": pose_count,
        "left_hand_frames": left_hand_count,
        "right_hand_frames": right_hand_count,
        "pose_coverage": pose_count / max(1, n),
        "left_hand_coverage": left_hand_count / max(1, n),
        "right_hand_coverage": right_hand_count / max(1, n),
        "outputs": {
            "overlay_video": str(output_video),
            "landmarks_json": str(out_dir / f"{args.label}_holistic_landmarks.json"),
            "viewer_html": str(out_dir / f"{args.label}_holistic_3d_viewer.html"),
        },
    }
    (out_dir / f"{args.label}_holistic_landmarks.json").write_text(json.dumps(frame_records, ensure_ascii=False), encoding="utf-8")
    (out_dir / f"{args.label}_holistic_metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    build_html(frame_records, metrics, out_dir / f"{args.label}_holistic_3d_viewer.html")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
