# ShuttlePoseReview Algorithm Evaluation

## Goal

ShuttlePoseReview should optimize for ordinary phone videos of the near-side player, not full-match shuttle tracking. The algorithm stack therefore needs to answer three questions:

1. Can it extract a stable near-player skeleton fast enough?
2. Can it produce action metrics that remain comparable under ordinary phone-camera variation?
3. Can the same route plausibly move to Android later?

## Current Pose Benchmark

Script:

```bash
work/Good-Badminton/.venv/bin/python work/scripts/benchmark_pose_algorithms.py \
  --video /Users/linkfair/Downloads/IMG_5868.MOV \
  --output-dir outputs/pose_algorithm_benchmark_img5868 \
  --label img5868_20s_stride2 \
  --start-sec 0 \
  --duration-sec 20.8 \
  --stride 2 \
  --max-width 960
```

### IMG_5868 Phone Video

| Algorithm | Inference FPS | Coverage | Core KP | Arm KP | Leg KP | Max Gap | Overall |
|---|---:|---:|---:|---:|---:|---:|---:|
| MediaPipe Pose | 69.94 | 100.0% | 95.3% | 90.7% | 100.0% | 0 | 94.6 |
| RTMPose Body | 11.28 | 100.0% | 97.4% | 94.7% | 100.0% | 0 | 95.1 |

Interpretation: On clean near-side phone video, both are usable. MediaPipe is much faster on local CPU and is the better default for a local Web MVP and future mobile MVP. RTMPose gives slightly stronger keypoint coverage but costs about 6x more CPU time in this run.

### Lin Dan 2008 Clip

| Algorithm | Inference FPS | Coverage | Core KP | Arm KP | Leg KP | Max Gap | Overall |
|---|---:|---:|---:|---:|---:|---:|---:|
| MediaPipe Pose | 57.09 | 85.0% | 75.0% | 84.8% | 65.2% | 4 | 78.7 |
| RTMPose Body | 9.70 | 88.0% | 81.0% | 91.7% | 70.3% | 8 | 75.2 |

Interpretation: Match footage is harder because the subject is smaller and there are multiple players. RTMPose improves arm/core coverage, but tracking continuity is not automatically better.

### Lin Dan 2011 Clip

| Algorithm | Inference FPS | Coverage | Core KP | Arm KP | Leg KP | Max Gap | Overall |
|---|---:|---:|---:|---:|---:|---:|---:|
| MediaPipe Pose | 54.95 | 81.0% | 74.3% | 69.7% | 79.0% | 5 | 72.0 |
| RTMPose Body | 5.89 | 93.0% | 92.2% | 94.7% | 89.7% | 3 | 84.7 |

Interpretation: For smaller players and broadcast-style footage, RTMPose can be meaningfully more stable, but CPU speed is currently not acceptable as the default mobile path.

## Recommendation

Use a two-tier pose strategy:

1. **Default path: MediaPipe Pose**
   - Best speed.
   - Strong enough for the intended input: short, near-side, phone-shot rallies.
   - Easiest route to Android/iOS because MediaPipe is already an edge-oriented stack.

2. **Quality path: RTMPose Body**
   - Better for smaller or less ideal subjects.
   - Useful as a server-side/offline comparison model.
   - Needs ONNX Runtime optimization, model-size testing, and Android benchmark before becoming a mobile option.

MoveNet remains a later candidate, but this environment currently does not include TensorFlow/TF Hub. It should be evaluated only after adding a clean TFLite/ONNX runner so the benchmark is comparable.

## Input Gate

Before scoring, the app should reject or warn on weak inputs:

- Video duration over 30 seconds.
- Near-side player height below roughly 35% of frame height.
- Core keypoint coverage below 85%.
- Arm keypoint coverage below 75%.
- Max missing gap above 6 sampled frames.
- Frequent center jumps, likely caused by tracking the wrong player.

This is not just UX. It prevents the scoring model from producing precise-looking numbers from weak pose data.
