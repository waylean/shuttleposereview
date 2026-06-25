# ShuttlePoseReview

ShuttlePoseReview is an action review project for badminton enthusiasts. It extracts 2D human skeletons from ordinary phone-recorded videos, detects obvious power-action windows, and breaks each power action down into three interpretable metrics: `Contact Timing`, `Kinetic Chain`, and `Recovery`.

The project aims to help amateur players see more clearly whether a shot was taken from a better position, whether power was transferred from the body to the wrist, and whether the player recovered in time for the next shot.

## Current Features

- Upload a badminton video and create a local review job.
- On Android, select a video from the phone gallery and run frame sampling, pose recognition, and review fully on device.
- Preprocess the video into an MP4 format that is easier for both the browser and the algorithm to handle.
- Use a human pose model to extract the near-side player's 2D skeleton.
- Overlay the skeleton on the video and generate a playable action review page.
- Export an MP4 video with the pose skeleton composited on top for saving, sharing, or editing.
- Cache reviewed videos locally so the last review can be opened directly without waiting for the same analysis again.
- Automatically detect obvious power-action windows, instead of treating every net shot, block, or transition shot as one power action.
- Output three scores and a full formula breakdown for each power-action window.
- Export structured JSON for training logs, cross-session comparison, and algorithm evaluation.

## Android APK Download

The recommended download channel is GitHub Releases:

```text
https://github.com/waylean/shuttleposereview/releases
```

For users who cannot access GitHub reliably, the maintainer may upload `app-debug.apk` or a later signed APK to Quark Cloud Drive and place the mirror link here:

```text
Quark Cloud Drive mirror: pending
```

Local debug APK build path:

```text
apps/android/app/build/outputs/apk/debug/app-debug.apk
```

Android build command:

```bash
cd apps/android
./gradlew :app:assembleDebug
```

Windows PowerShell:

```powershell
cd apps/android
.\gradlew.bat :app:assembleDebug
```

After installation, tap "选择或导入视频", wait for the review to finish, then view the skeleton-overlay video, power-action timeline, and three action metrics. The "下载姿态合成视频" button exports an MP4 with the skeleton overlay.

## Promo Video Materials

For a vertical introduction video, see:

```text
docs/hyperframes_promo_prompt.md
docs/promo_subtitles_zh.srt
```

`hyperframes_promo_prompt.md` can be used with HyperFrames to generate a no-voiceover video. `promo_subtitles_zh.srt` can be imported into CapCut, Premiere, or another editor before adding your own narration.

## Demo

![ShuttlePoseReview demo](assets/demo/badminton_review_demo.gif)

Demo video file:

```text
assets/demo/badminton_review_demo.mp4
```

## Recommended Videos

For more stable results, the input video should preferably meet the following conditions:

- Keep the video short, ideally a continuous rally or a short continuous multi-shot sequence.
- Avoid uploading a long full match or full training session directly.
- The near-side player should be clearly visible, without long-term occlusion of the body, arms, or footwork.
- Low phone angles, side-back views, and rear views can all be tested, but trend comparison for the same player is more meaningful when the camera setup stays similar.
- The current version is better suited for obvious swing actions such as smashes, clears, drives, and power transitions. Small actions such as net shots and light blocks may not be counted as separate power-action windows.

## Workflow

```text
Upload video
  -> Video preprocessing
  -> Human 2D pose extraction
  -> Near-side skeleton and action-window analysis
  -> Three-metric scoring
  -> Generate review page, overlay video, and JSON result
```

Local Web MVP instructions:

```text
apps/web/README.md
```

Main algorithm implementation:

```text
work/scripts/build_2d_action_review.py
```

Full formula specification:

```text
docs/scoring_formula_spec.md
```

## Three Metrics

ShuttlePoseReview currently does not directly determine real shuttle speed or real physical force. The system first computes joint angles, speeds, relative height, torso scale, and action windows from the skeleton, then converts these observable proxy variables into action review metrics.

### 1. Contact Timing

Contact Timing answers the question: did this shot complete its power action from a better position and preparation state?

Core observations:

| Component | Meaning |
|---|---|
| Wrist height | Whether the wrist is near a better high-contact position around the power point |
| Elbow angle at event | Whether the elbow is in a reasonable extension range for hitting |
| Preparation posture | Whether there are signs of racket preparation, hand lift, side-on posture, or shoulder-hip opening before contact |
| Active-arm visibility | Whether the current skeleton data is readable enough |

Formula summary:

```text
timing_score
  = clamp(
      0.46 * height_score
    + 0.24 * elbow_score
    + 0.20 * prep_score
    + 0.10 * confidence_score
    )
```

Where:

```text
height_score = 0.55 * max_height_score + 0.45 * contact_height_score
elbow_score  = band_score(elbow_angle_at_event; ideal=145°, tolerance=70°)
prep_score   = 0.62 * prep_height_score + 0.38 * twist_score
```

Rationale:

- Badminton power actions usually need better contact height and earlier preparation.
- A straighter elbow is not always better; the elbow should be in a workable extension range.
- Preparation posture helps distinguish an early prepared stroke from a rushed arm swing.

### 2. Kinetic Chain

Kinetic Chain answers the question: does this shot show a continuous acceleration rhythm from the lower body and trunk to the arm and wrist?

The system does not only look at the maximum speed of a single frame. Instead, it cuts three time windows around event frame `e`:

```text
leg_band       = [e - 0.65F, e - 0.22F]
trunk_arm_band = [e - 0.38F, e - 0.06F]
wrist_band     = [e - 0.18F, e + 0.08F]
```

Here, `F` is the video frame rate.

Energy proxies:

```text
leg_energy   = P80(knee_angular_speed in leg_band)
trunk_energy = P80(abs(twist_t - twist_{t-1}) * F in trunk_arm_band)
elbow_energy = P82(elbow_angular_speed in trunk_arm_band)
wrist_energy = P88(normalized_wrist_speed in wrist_band)
```

Combined formula:

```text
chain_score
  = clamp(
      0.32 * energy_score
    + 0.26 * order_score
    + 0.18 * wrist_late_score
    + 0.14 * knee_load_score
    + 0.10 * confidence_score
    )
```

Rationale:

- `energy_score` checks whether this shot has obvious motion energy.
- `order_score` checks whether the energy center roughly moves from the lower body, through the trunk and elbow, toward the wrist.
- `wrist_late_score` checks whether the wrist speed peak is close to the power-action event frame.
- `knee_load_score` uses pre-contact knee flexion as a simplified proxy for lower-body involvement.

### 3. Recovery

Recovery answers the question: after this shot, can the body return quickly to a state that can connect to the next shot?

Recovery window:

```text
recover = [e + 0.12F, e + 1.20F]
```

Stable-frame conditions:

```text
normalized_wrist_speed <= 1.15
elbow_angular_speed    <= 360°/s
knee_angular_speed     <= 300°/s
```

Combined formula:

```text
recovery_score
  = clamp(
      0.54 * recovery_time_score
    + 0.28 * residual_score
    + 0.18 * posture_score
    )
```

Rationale:

- A shorter recovery time means the player returns to a controllable state more quickly after the shot.
- Smaller residual motion means the body does not continue to swing widely after the follow-through.
- Reduced shoulder-hip separation means the body posture is closer to the next ready state.

## Important Boundaries

The current metrics are more suitable for:

- Trend comparison for the same player, under the same or similar camera setup, and with similar action types.
- Helping amateur players identify likely action issues.
- Providing structured references for training review.

The current metrics are not suitable for:

- Directly comparing absolute levels across different camera angles, distances, and players.
- Measuring real shuttle speed.
- Recovering a true 3D kinetic chain or true muscle output.

## Run the Web MVP

The current Web MVP runs in a Python virtual environment:

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python -m uvicorn apps.web.main:app --host 127.0.0.1 --port 8787
```

Open:

```text
http://127.0.0.1:8787
```

If the port is occupied, use another one:

```bash
python -m uvicorn apps.web.main:app --host 127.0.0.1 --port 8788
```

The system also requires an available `ffmpeg` command for video preprocessing.

## Output Files

One analysis usually generates:

```text
*_2d_action_review.html    # Interactive review page
*_2d_review_overlay.mp4    # Skeleton overlay video
*_2d_action_review.json    # Structured analysis result
```

The full formula evidence for each power-action window is located at:

```text
stroke_metrics[].score_breakdown
```

This allows each total score to be traced back to its specific windows, angles, speeds, sub-scores, and weights.

## Next Directions

- Build a manually labeled dataset: power-action windows, contact timing, kinetic chain, and recovery quality.
- Run camera perturbation experiments: crop, scale, and rotate videos to observe score drift.
- Validate correlation with coach scoring: use Spearman correlation to evaluate whether the three metrics align with human judgment.
- Evaluate lighter pose models and explore Android / iOS deployment.
- Segment long videos into short rallies to reduce waiting time and false detections.

## Acknowledgements

This project is inspired and supported by multiple open-source projects and tools, including:

- Good-Badminton: important inspiration for badminton video analysis.
- Va6lue/BST-Badminton-Stroke-type-Transformer (BST/BTS): reference ideas for understanding badminton stroke types from skeleton sequences.
- MediaPipe: human pose and skeleton landmark extraction.
- OpenCV: video processing, frame reading, and skeleton overlay.
- FFmpeg: video preprocessing and format conversion.
- FastAPI / Uvicorn: upload and job service for the local Web MVP.
