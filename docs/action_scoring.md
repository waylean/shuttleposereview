# Action Scoring Design

## Principle

The score must describe what can be observed from a single phone video. It should not claim true shuttle speed, true 3D biomechanics, or absolute professional comparison.

The current three product metrics are still useful, but they need clearer definitions:

1. **击球时机**
   - Is there a readable hitting window?
   - Does the wrist accelerate around a plausible contact moment?
   - Is the wrist position consistent with an overhead/forehand badminton action?

2. **发力链**
   - Does the action show a sequence from leg load/release, trunk rotation/opening, elbow extension, to wrist acceleration?
   - This is a coordination score, not a true force or shuttle-speed score.

3. **回位恢复**
   - After the hit window, does the body quickly return to a stable ready posture?
   - Is residual arm/body motion reduced before the next action?

## Current Feature Sources

All current features come from 2D pose landmarks:

- Shoulders: 11, 12
- Elbows: 13, 14
- Wrists: 15, 16
- Hips: 23, 24
- Knees: 25, 26
- Ankles: 27, 28

Main derived features:

- Normalized wrist speed: wrist pixel speed divided by torso/body scale.
- Elbow angle and elbow angular speed.
- Knee angle and knee angular speed.
- Wrist height relative to shoulder, normalized by torso/body scale.
- Shoulder-hip separation angle.
- Post-hit residual motion.
- Active-arm confidence and low-confidence ratio.

## Sensitivity Result

Script:

```bash
work/Good-Badminton/.venv/bin/python work/scripts/analyze_action_score_sensitivity.py \
  --review-json outputs/2d_action_review_img5868/img5868_2d_action_review.json \
  --output-dir outputs/action_score_sensitivity_img5868 \
  --label img5868_current_scores
```

Summary:

| Transform | ΔTiming | ΔChain | ΔRecovery | Max Δ |
|---|---:|---:|---:|---:|
| rotate_left_8 | 1.0 | 0.0 | 0.2 | 1.0 |
| rotate_right_8 | 1.2 | 0.0 | 1.2 | 4.0 |
| low_angle_y_stretch | 0.6 | 0.0 | 0.6 | 2.0 |
| x_scale_075 | 1.6 | 1.6 | 0.8 | 7.0 |
| x_scale_125 | 1.2 | 6.0 | 4.8 | 19.0 |
| side_shear | 0.2 | 3.0 | 0.2 | 15.0 |

Interpretation:

- Timing is relatively stable under mild 2D camera perturbation.
- Recovery is mostly stable, but can drift when horizontal scale changes because body-center and residual-motion measurements change.
- Force-chain scoring is the least reliable because it depends on peak ordering of knee, trunk, elbow, and wrist. Small geometric changes can shift angular-speed peaks.

## Implemented V2 Update

The current implementation now treats detected events as **重发力窗口**, not total hit count.

This matters because a badminton rally includes many actions that are real hits but should not always become scored events:

- net drops.
- light blocks.
- transition shots.
- defensive touches.
- small preparation motions between heavy strokes.

The app currently detects obvious wrist-speed and body-action peaks. Therefore the product wording should say:

> Detected N heavy-action windows, not N total shuttle contacts.

The JSON summary now includes:

- `event_semantics`
- `power_action_frames`
- `power_action_count`

### Force-Chain V2

The force-chain score no longer depends mainly on exact peak-frame ordering. It now uses action energy in time windows around the heavy-action frame:

- leg loading/release: `contact -0.65s` to `contact -0.22s`
- trunk/arm acceleration: `contact -0.38s` to `contact -0.06s`
- wrist whip: `contact -0.18s` to `contact +0.08s`

The score combines:

- leg energy.
- trunk/shoulder-hip energy.
- elbow acceleration energy.
- wrist whip energy.
- soft temporal ordering across the bands.
- knee load range.
- active-arm confidence.

This is more stable than requiring the knee, trunk, elbow, and wrist peaks to occur in one exact order.

### IMG_5870 V2 Result

Detected heavy-action windows: `6`

| Window | Timing | Chain V1 | Chain V2 | Recovery |
|---:|---:|---:|---:|---:|
| 1 | 68 | 36 | 59 | 78 |
| 2 | 89 | 75 | 75 | 48 |
| 3 | 70 | 51 | 54 | 48 |
| 4 | 86 | 77 | 83 | 29 |
| 5 | 80 | 75 | 70 | 40 |
| 6 | 84 | 80 | 84 | 45 |

V2 improves the first window because it had clear leg/trunk/elbow/wrist energy but the old exact peak ordering punished it too heavily.

Sensitivity after V2:

| Transform | ΔTiming | ΔChain | ΔRecovery | Max Δ |
|---|---:|---:|---:|---:|
| rotate_left_8 | 0.67 | 0.17 | 1.83 | 6 |
| rotate_right_8 | 0.83 | 0.33 | 0.83 | 3 |
| low_angle_y_stretch | 1.00 | 0.50 | 1.67 | 6 |
| side_shear | 0.33 | 0.67 | 3.67 | 9 |
| x_scale_075 | 2.17 | 1.50 | 2.17 | 4 |
| x_scale_125 | 2.33 | 1.50 | 3.50 | 10 |

The force-chain average drift stays around `0.17-1.50` points under these synthetic 2D perturbations.

## Next Scoring Upgrade

### 1. Use Input Quality As A Gate

Do not generate strong-looking scores unless the input passes:

- Pose continuity.
- Active arm confidence.
- Person size in frame.
- Stable subject tracking.
- View category: rear/side/front/unknown.

If the gate fails, the product should output "可复盘，但不建议打分" or "仅展示骨架与阶段".

### 2. Replace Hard Peak Ordering With Soft Windows

Current force-chain scoring checks whether knee, trunk, elbow, and wrist peaks happen in order. This is fragile.

Better:

- Compute energy in time bands before contact:
  - leg-load band: contact -0.65s to -0.25s
  - trunk/arm acceleration band: contact -0.35s to -0.08s
  - wrist whip band: contact -0.18s to +0.06s
- Score whether the dominant energy moves later in time, instead of requiring exact peak frame order.

### 3. Use Relative Ranges, Not Absolute Angles Alone

For amateur videos, absolute elbow/knee angle is viewpoint sensitive.

Prefer:

- elbow extension range inside the stroke window.
- knee flexion range before contact.
- wrist speed percentile relative to the same video.
- post-hit residual motion relative to pre-hit movement.

### 4. Score One Video Against Itself First

Before building a professional comparison baseline, the most reliable product value is intra-video comparison:

- Which stroke in this rally had the clearest hit window?
- Which stroke recovered slowest?
- Which stroke had the strongest whip-like wrist acceleration?

Only after collecting enough same-camera user data should the app compare across users or against professional references.

## Proposed V2 Metrics

### 击球时机 V2

Inputs:

- normalized wrist speed peak sharpness.
- wrist-above-shoulder ratio.
- active-arm confidence.
- event isolation from neighboring peaks.

Stable under camera changes: medium-high.

### 发力链 V2

Inputs:

- knee flexion range before contact.
- trunk/shoulder-hip activity energy.
- elbow extension range.
- wrist acceleration energy near contact.
- soft temporal ordering across bands.

Stable under camera changes: medium. Needs view gate.

### 回位恢复 V2

Inputs:

- time until normalized wrist/body motion drops below threshold.
- center-of-body stabilization.
- knee/hip return toward ready posture.
- no large tracking switch after contact.

Stable under camera changes: medium-high if tracking is stable.
