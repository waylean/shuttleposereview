import argparse
import json
import math
import shutil
import subprocess
from pathlib import Path

import cv2
import numpy as np


POSE_CONNECTIONS = [
    (11, 12),
    (11, 13),
    (13, 15),
    (12, 14),
    (14, 16),
    (11, 23),
    (12, 24),
    (23, 24),
    (23, 25),
    (25, 27),
    (24, 26),
    (26, 28),
]

LEFT_ARM = (11, 13, 15)
RIGHT_ARM = (12, 14, 16)
LEFT_LEG = (23, 25, 27)
RIGHT_LEG = (24, 26, 28)
PHASE_COLORS = {
    "ready": (120, 140, 160),
    "backswing": (255, 190, 60),
    "drive": (80, 210, 255),
    "contact": (80, 80, 255),
    "follow": (210, 120, 255),
    "recover": (80, 210, 120),
}


def point(frame, idx):
    pose = frame.get("pose") or []
    if idx >= len(pose) or pose[idx] is None:
        return None
    p = pose[idx]
    return np.array([float(p["px"]), float(p["py"])], dtype=np.float32)


def visibility(frame, idx):
    pose = frame.get("pose") or []
    if idx >= len(pose) or pose[idx] is None:
        return 0.0
    return float(pose[idx].get("visibility", 1.0))


def angle_deg(a, b, c):
    if a is None or b is None or c is None:
        return None
    va = np.asarray(a) - np.asarray(b)
    vc = np.asarray(c) - np.asarray(b)
    denom = float(np.linalg.norm(va) * np.linalg.norm(vc))
    if denom <= 1e-6:
        return None
    return float(np.degrees(np.arccos(np.clip(np.dot(va, vc) / denom, -1, 1))))


def line_angle(a, b):
    if a is None or b is None:
        return None
    dx, dy = np.asarray(b) - np.asarray(a)
    return float(np.degrees(np.arctan2(dy, dx)))


def angle_delta(a, b):
    if a is None or b is None:
        return None
    d = (a - b + 180.0) % 360.0 - 180.0
    return float(abs(d))


def smooth_series(values, alpha=0.45):
    out = []
    prev = None
    for item in values:
        if item is None:
            out.append(prev.copy() if prev is not None else None)
            continue
        cur = np.asarray(item, dtype=np.float32)
        if prev is None:
            prev = cur
        else:
            prev = prev * (1.0 - alpha) + cur * alpha
        out.append(prev.copy())
    return out


def speed(points, fps):
    vals = [0.0]
    for prev, cur in zip(points, points[1:]):
        if prev is None or cur is None:
            vals.append(0.0)
        else:
            vals.append(float(np.linalg.norm(cur - prev) * fps))
    return vals


def clamp(value, low=0.0, high=100.0):
    return float(max(low, min(high, value)))


def score_range(value, low, high, invert=False):
    if value is None:
        return 50.0
    if abs(high - low) <= 1e-6:
        return 50.0
    raw = (float(value) - low) / (high - low)
    if invert:
        raw = 1.0 - raw
    return clamp(raw * 100.0)


def score_band(value, ideal, tolerance):
    if value is None:
        return 50.0
    return clamp(100.0 - abs(float(value) - ideal) / max(tolerance, 1e-6) * 55.0)


def median_value(values, default=0.0):
    vals = [float(v) for v in values if v is not None and np.isfinite(float(v))]
    return float(np.median(vals)) if vals else default


def percentile_value(values, pct, default=0.0):
    vals = [float(v) for v in values if v is not None and np.isfinite(float(v))]
    return float(np.percentile(vals, pct)) if vals else default


def robust_events(values, fps, limit=6):
    arr = np.asarray(values, dtype=np.float32)
    if len(arr) < 5 or float(arr.max()) <= 0:
        return []
    threshold = max(float(np.percentile(arr, 86)), float(arr.max()) * 0.38)
    gap = int(max(14, fps * 0.75))
    candidates = []
    for i in range(2, len(arr) - 2):
        if arr[i] < threshold:
            continue
        if arr[i] >= arr[i - 1] and arr[i] >= arr[i + 1]:
            candidates.append((float(arr[i]), i))
    candidates.sort(reverse=True)
    selected = []
    for _, idx in candidates:
        if all(abs(idx - old) >= gap for old in selected):
            selected.append(idx)
        if len(selected) >= limit:
            break
    return sorted(selected)


def wrist_height(frame, side):
    shoulder, _, wrist = LEFT_ARM if side == "left" else RIGHT_ARM
    sh = point(frame, shoulder)
    wr = point(frame, wrist)
    if sh is None or wr is None:
        return 0.0
    return float(sh[1] - wr[1])


def action_scores(raw_frames, left_speed, right_speed):
    scores = []
    for idx, frame in enumerate(raw_frames):
        left_h = wrist_height(frame, "left")
        right_h = wrist_height(frame, "right")
        left_conf = min(visibility(frame, 11), visibility(frame, 13), visibility(frame, 15))
        right_conf = min(visibility(frame, 12), visibility(frame, 14), visibility(frame, 16))

        def score(v, h, conf):
            height_bonus = 1.0 + max(0.0, h) / 50.0
            low_height_penalty = max(0.0, -h) * 8.0
            # Do not suppress low-confidence overhead candidates completely;
            # side-on badminton strokes are exactly where elbow/wrist confidence drops.
            conf_floor = 0.65 + min(0.35, max(0.0, conf) * 0.35)
            return max(0.0, v * height_bonus * conf_floor - low_height_penalty)

        scores.append(max(score(left_speed[idx], left_h, left_conf), score(right_speed[idx], right_h, right_conf)))
    return scores


def phase_name(idx, events, fps):
    if not events:
        return "ready"
    nearest = min(events, key=lambda x: abs(x - idx))
    dt = idx - nearest
    if dt < -0.95 * fps:
        return "ready"
    if dt < -0.42 * fps:
        return "backswing"
    if dt < -0.10 * fps:
        return "drive"
    if dt <= 0.10 * fps:
        return "contact"
    if dt <= 0.58 * fps:
        return "follow"
    return "recover"


def active_side(frame, left_speed, right_speed):
    ls, le, lw = LEFT_ARM
    rs, re, rw = RIGHT_ARM
    lp = point(frame, lw)
    rp = point(frame, rw)
    lsh = point(frame, ls)
    rsh = point(frame, rs)
    left_height = max(0.0, float(lsh[1] - lp[1])) if lp is not None and lsh is not None else 0.0
    right_height = max(0.0, float(rsh[1] - rp[1])) if rp is not None and rsh is not None else 0.0
    left_conf = min(visibility(frame, ls), visibility(frame, le), visibility(frame, lw))
    right_conf = min(visibility(frame, rs), visibility(frame, re), visibility(frame, rw))
    left_score = left_speed * (1.0 + max(0.0, left_height) / 65.0) - max(0.0, -left_height) * 8.0 + left_conf * 12.0
    right_score = right_speed * (1.0 + max(0.0, right_height) / 65.0) - max(0.0, -right_height) * 8.0 + right_conf * 12.0
    return "left" if left_score >= right_score else "right"


def side_indices(side):
    return (LEFT_ARM, LEFT_LEG) if side == "left" else (RIGHT_ARM, RIGHT_LEG)


def compact_pose(frame):
    pose = frame.get("pose") or []
    out = []
    for p in pose:
        if p is None:
            out.append(None)
        else:
            out.append([round(float(p["px"]), 2), round(float(p["py"]), 2), round(float(p.get("visibility", 1.0)), 3)])
    return out


def pose_bbox(frame, padding_ratio=0.38):
    pose = compact_pose(frame)
    pts = np.asarray([[p[0], p[1]] for p in pose[11:29] if p is not None and p[2] >= 0.18], dtype=np.float32)
    if len(pts) == 0:
        return None
    x1, y1 = pts.min(axis=0)
    x2, y2 = pts.max(axis=0)
    width = max(1.0, float(x2 - x1))
    height = max(1.0, float(y2 - y1))
    pad = max(width, height) * padding_ratio
    return [round(float(x1 - pad), 2), round(float(y1 - pad), 2), round(float(x2 + pad), 2), round(float(y2 + pad), 2)]


def frame_metrics(frame, fps, idx, left_speed, right_speed, events):
    side = active_side(frame, left_speed[idx], right_speed[idx])
    arm, leg = side_indices(side)
    shoulder, elbow, wrist = arm
    hip, knee, ankle = leg
    sh = point(frame, shoulder)
    el = point(frame, elbow)
    wr = point(frame, wrist)
    hp = point(frame, hip)
    kn = point(frame, knee)
    an = point(frame, ankle)
    opp_sh = point(frame, 12 if side == "left" else 11)
    opp_hp = point(frame, 24 if side == "left" else 23)
    arm_conf = min(visibility(frame, shoulder), visibility(frame, elbow), visibility(frame, wrist))
    wrist_above = float(sh[1] - wr[1]) if sh is not None and wr is not None else None
    shoulder_angle = line_angle(point(frame, 11), point(frame, 12))
    hip_angle = line_angle(point(frame, 23), point(frame, 24))
    twist = angle_delta(shoulder_angle, hip_angle)
    torso_len = float(np.linalg.norm(((point(frame, 11) + point(frame, 12)) * 0.5) - ((point(frame, 23) + point(frame, 24)) * 0.5))) if all(point(frame, j) is not None for j in [11, 12, 23, 24]) else None
    warnings = []
    if arm_conf < 0.35:
        warnings.append("active_arm_low_confidence")
    elif arm_conf < 0.55:
        warnings.append("active_arm_medium_confidence")
    if frame.get("left_hand") is None and frame.get("right_hand") is None:
        warnings.append("hand_not_detected")
    return {
        "frame": idx,
        "time_sec": round(float(frame.get("time_sec", idx / fps)), 3),
        "phase": phase_name(idx, events, fps),
        "active_side": side,
        "wrist_speed_px_s": round(float(max(left_speed[idx], right_speed[idx])), 2),
        "left_wrist_speed_px_s": round(float(left_speed[idx]), 2),
        "right_wrist_speed_px_s": round(float(right_speed[idx]), 2),
        "elbow_angle_deg": round(angle_deg(sh, el, wr), 1) if angle_deg(sh, el, wr) is not None else None,
        "knee_angle_deg": round(angle_deg(hp, kn, an), 1) if angle_deg(hp, kn, an) is not None else None,
        "wrist_above_shoulder_px": round(wrist_above, 1) if wrist_above is not None else None,
        "shoulder_hip_separation_deg": round(twist, 1) if twist is not None else None,
        "torso_length_px": round(torso_len, 1) if torso_len is not None else None,
        "active_arm_confidence": round(float(arm_conf), 3),
        "left_elbow_visibility": round(visibility(frame, 13), 3),
        "left_wrist_visibility": round(visibility(frame, 15), 3),
        "right_elbow_visibility": round(visibility(frame, 14), 3),
        "right_wrist_visibility": round(visibility(frame, 16), 3),
        "left_hand_detected": frame.get("left_hand") is not None,
        "right_hand_detected": frame.get("right_hand") is not None,
        "warnings": warnings,
        "pose2d": compact_pose(frame),
        "bbox": pose_bbox(frame),
    }


def summarize_event(records, event_frame, fps):
    start = max(0, int(event_frame - fps * 0.65))
    end = min(len(records) - 1, int(event_frame + fps * 0.65))
    window = records[start : end + 1]
    peak = max(window, key=lambda r: r["wrist_speed_px_s"])
    contact = records[event_frame]
    low_conf_ratio = sum(1 for r in window if r["active_arm_confidence"] < 0.45) / max(1, len(window))
    wrist_heights = [r["wrist_above_shoulder_px"] for r in window if r["wrist_above_shoulder_px"] is not None]
    elbow_angles = [r["elbow_angle_deg"] for r in window if r["elbow_angle_deg"] is not None]
    notes = []
    if low_conf_ratio > 0.35:
        notes.append("侧身/遮挡明显，建议结合原视频查看肘腕位置")
    if wrist_heights and max(wrist_heights) > 0:
        notes.append("发力窗口手腕有高于肩部的架拍/击球迹象")
    if elbow_angles and min(elbow_angles) < 45:
        notes.append("发力窗口肘角变化很大，建议作为节奏参考")
    if not notes:
        notes.append("动作窗口可读，但还需要更多样本建立个人基准")
    return {
        "event_frame": event_frame,
        "time_sec": records[event_frame]["time_sec"],
        "event_type": "power_action",
        "event_label": "重发力窗口",
        "window": [start, end],
        "active_side": contact["active_side"],
        "peak_wrist_speed_px_s": peak["wrist_speed_px_s"],
        "peak_frame": peak["frame"],
        "contact_elbow_angle_deg": contact["elbow_angle_deg"],
        "contact_knee_angle_deg": contact["knee_angle_deg"],
        "max_wrist_above_shoulder_px": round(max(wrist_heights), 1) if wrist_heights else None,
        "low_confidence_ratio": round(low_conf_ratio, 3),
        "notes": notes,
    }


def stroke_quality_scores(records, event_idx, start, end, fps):
    pre_start = start
    contact_end = min(len(records) - 1, int(event_idx + fps * 0.10))
    recover_start = min(len(records) - 1, int(event_idx + fps * 0.12))
    recover_end = min(len(records) - 1, int(event_idx + fps * 1.20))
    pre = records[pre_start : event_idx + 1]
    stroke_window = records[pre_start : contact_end + 1]
    recover = records[recover_start : recover_end + 1]
    contact = records[event_idx]

    torso = max(median_value([r.get("torso_length_px") for r in stroke_window], 65.0), 45.0)
    arm_conf = median_value([r.get("active_arm_confidence") for r in stroke_window], 0.45)
    low_conf_ratio = sum(1 for r in stroke_window if float(r.get("active_arm_confidence") or 0.0) < 0.45) / max(1, len(stroke_window))
    confidence_score = clamp((arm_conf - 0.25) / 0.65 * 100.0)

    wrist_heights = [r.get("wrist_above_shoulder_px") for r in stroke_window if r.get("wrist_above_shoulder_px") is not None]
    contact_height_ratio = float(contact.get("wrist_above_shoulder_px") or 0.0) / torso
    max_height_ratio = (max(wrist_heights) / torso) if wrist_heights else contact_height_ratio
    max_height_score = score_range(max_height_ratio, -0.15, 0.95)
    contact_height_score = score_range(contact_height_ratio, -0.35, 0.75)
    height_score = 0.55 * max_height_score + 0.45 * contact_height_score

    elbow_score = score_band(contact.get("elbow_angle_deg"), 145.0, 70.0)
    prep_cut = max(pre_start, int(event_idx - fps * 0.38))
    prep = records[pre_start : prep_cut + 1]
    prep_height_ratio = max([float(r.get("wrist_above_shoulder_px") or -torso) / torso for r in prep], default=-0.5)
    prep_height_score = score_range(prep_height_ratio, -0.35, 0.80)
    max_twist = max([float(r.get("shoulder_hip_separation_deg") or 0.0) for r in pre], default=0.0)
    twist_score = score_range(max_twist, 4.0, 24.0)
    prep_score = 0.62 * prep_height_score + 0.38 * twist_score
    timing_score = clamp(0.46 * height_score + 0.24 * elbow_score + 0.20 * prep_score + 0.10 * confidence_score)

    def peak_frame(items, field, value_fn=float):
        candidates = []
        for r in items:
            val = r.get(field)
            if val is None:
                continue
            candidates.append((value_fn(val), r["frame"]))
        return max(candidates)[1] if candidates else None

    chain_window = records[pre_start : contact_end + 1]
    wrist_peak_frame = peak_frame(chain_window, "normalized_wrist_speed_body_s")
    elbow_peak_frame = peak_frame(chain_window, "elbow_angular_speed_deg_s")
    knee_peak_frame = peak_frame(chain_window, "knee_angular_speed_deg_s")

    twist_by_frame = []
    prev_twist = None
    for r in chain_window:
        cur = r.get("shoulder_hip_separation_deg")
        if cur is None or prev_twist is None:
            val = 0.0
        else:
            val = abs(float(cur) - float(prev_twist)) * fps
        prev_twist = cur if cur is not None else prev_twist
        twist_by_frame.append((r["frame"], val))
    twist_peak_frame = max(twist_by_frame, key=lambda item: item[1])[0] if twist_by_frame else None

    def band_items(start_offset, end_offset):
        lo = event_idx + int(round(start_offset * fps))
        hi = event_idx + int(round(end_offset * fps))
        return [r for r in records[max(0, lo) : min(len(records), hi + 1)]]

    leg_band = band_items(-0.65, -0.22)
    trunk_arm_band = band_items(-0.38, -0.06)
    wrist_band = band_items(-0.18, 0.08)

    leg_energy = percentile_value([r.get("knee_angular_speed_deg_s") for r in leg_band], 80, 0.0)
    trunk_energy = percentile_value(
        [
            abs(float(r.get("shoulder_hip_separation_deg") or 0.0) - float(prev.get("shoulder_hip_separation_deg") or 0.0)) * fps
            for prev, r in zip(trunk_arm_band, trunk_arm_band[1:])
        ],
        80,
        0.0,
    )
    elbow_energy = percentile_value([r.get("elbow_angular_speed_deg_s") for r in trunk_arm_band], 82, 0.0)
    wrist_energy = percentile_value([r.get("normalized_wrist_speed_body_s") for r in wrist_band], 88, 0.0)

    leg_energy_score = score_range(leg_energy, 90.0, 520.0)
    trunk_energy_score = score_range(trunk_energy, 35.0, 260.0)
    elbow_energy_score = score_range(elbow_energy, 220.0, 1350.0)
    wrist_energy_score = score_range(wrist_energy, 2.2, 10.5)

    energy_score = clamp(
        0.20 * leg_energy_score
        + 0.18 * trunk_energy_score
        + 0.24 * elbow_energy_score
        + 0.38 * wrist_energy_score
    )

    def energy_center(items, field, transform=float):
        weighted = []
        for r in items:
            raw = r.get(field)
            if raw is None:
                continue
            val = max(0.0, transform(raw))
            if val <= 0:
                continue
            weighted.append((r["frame"], val))
        if not weighted:
            return None
        total = sum(v for _, v in weighted)
        return sum(f * v for f, v in weighted) / total

    leg_center = energy_center(leg_band, "knee_angular_speed_deg_s")
    elbow_center = energy_center(trunk_arm_band, "elbow_angular_speed_deg_s")
    wrist_center = energy_center(wrist_band, "normalized_wrist_speed_body_s")
    trunk_center = None
    if len(trunk_arm_band) > 1:
        weighted_twist = []
        for prev, r in zip(trunk_arm_band, trunk_arm_band[1:]):
            val = abs(float(r.get("shoulder_hip_separation_deg") or 0.0) - float(prev.get("shoulder_hip_separation_deg") or 0.0)) * fps
            if val > 0:
                weighted_twist.append((r["frame"], val))
        if weighted_twist:
            total = sum(v for _, v in weighted_twist)
            trunk_center = sum(f * v for f, v in weighted_twist) / total

    order_pairs = [(leg_center, trunk_center), (trunk_center, elbow_center), (elbow_center, wrist_center)]
    valid_order_pairs = [(a, b) for a, b in order_pairs if a is not None and b is not None]
    pair_scores = []
    order_pair_breakdown = []
    if not valid_order_pairs:
        order_score = 50.0
    else:
        for a, b in valid_order_pairs:
            # Softly reward later downstream energy instead of requiring one exact peak frame.
            diff = (b - a) / max(fps, 1.0)
            score = score_range(diff, -0.08, 0.22)
            pair_scores.append(score)
            order_pair_breakdown.append({
                "from_frame": round(a, 4),
                "to_frame": round(b, 4),
                "diff_sec": round(diff, 4),
                "score": round(score, 4),
            })
        order_score = float(np.mean(pair_scores))

    wrist_late_score = 50.0
    if wrist_peak_frame is not None:
        wrist_late_score = score_range(abs(wrist_peak_frame - event_idx) / max(fps, 1.0), 0.38, 0.02)
    knee_bends = [max(0.0, 180.0 - float(r["knee_angle_deg"])) for r in pre if r.get("knee_angle_deg") is not None]
    knee_load_score = score_range(max(knee_bends) if knee_bends else 0.0, 12.0, 72.0)
    chain_score = clamp(
        0.32 * energy_score
        + 0.26 * order_score
        + 0.18 * wrist_late_score
        + 0.14 * knee_load_score
        + 0.10 * confidence_score
    )

    stable_frame = None
    for r in recover:
        if (
            float(r.get("normalized_wrist_speed_body_s") or 0.0) <= 1.15
            and float(r.get("elbow_angular_speed_deg_s") or 0.0) <= 360.0
            and float(r.get("knee_angular_speed_deg_s") or 0.0) <= 300.0
        ):
            stable_frame = r["frame"]
            break
    if stable_frame is None:
        recovery_seconds = (recover_end - event_idx) / max(fps, 1.0)
        recovery_time_score = 32.0
    else:
        recovery_seconds = max(0.0, (stable_frame - event_idx) / max(fps, 1.0))
        recovery_time_score = score_range(recovery_seconds, 1.20, 0.28)
    residual_motion = median_value([r.get("normalized_wrist_speed_body_s") for r in recover], 1.2)
    residual_score = score_range(residual_motion, 1.70, 0.35)
    posture_median = median_value([r.get("shoulder_hip_separation_deg") for r in recover], 16.0)
    posture_score = score_range(posture_median, 26.0, 5.0)
    recovery_score = clamp(0.54 * recovery_time_score + 0.28 * residual_score + 0.18 * posture_score)

    def reliability(kind):
        base = confidence_score - low_conf_ratio * 34.0
        if kind == "chain":
            base -= 12.0
        if kind == "recovery":
            base -= 4.0
        if base >= 72.0:
            return "中高"
        if base >= 50.0:
            return "中"
        return "低"

    notes = []
    if low_conf_ratio > 0.35:
        notes.append("侧身或遮挡较多，肘腕相关分数需要结合原视频查看")
    if len(valid_order_pairs) < 2:
        notes.append("发力链能量顺序缺少部分关节数据")
    if stable_frame is None:
        notes.append("击球后 1.2 秒内没有稳定回位帧，回位分偏保守")
    if not notes:
        notes.append("本拍骨架连续性较好，适合做同机位趋势对比")

    def r4(value):
        if value is None:
            return None
        return round(float(value), 4)

    score_breakdown = {
        "common": {
            "event_frame": event_idx,
            "window": [start, end],
            "calculation_window": [pre_start, contact_end],
            "torso_median_px": r4(torso),
            "arm_conf_median": r4(arm_conf),
            "confidence_score": r4(confidence_score),
            "low_confidence_ratio": r4(low_conf_ratio),
        },
        "timing": {
            "contact_height_ratio": r4(contact_height_ratio),
            "max_height_ratio": r4(max_height_ratio),
            "max_height_score": r4(max_height_score),
            "contact_height_score": r4(contact_height_score),
            "height_score": r4(height_score),
            "contact_elbow_angle_deg": r4(contact.get("elbow_angle_deg")),
            "elbow_score": r4(elbow_score),
            "prep_window": [pre_start, prep_cut],
            "prep_height_ratio": r4(prep_height_ratio),
            "prep_height_score": r4(prep_height_score),
            "max_twist_deg": r4(max_twist),
            "twist_score": r4(twist_score),
            "prep_score": r4(prep_score),
            "confidence_score": r4(confidence_score),
            "weights": {"height": 0.46, "elbow": 0.24, "prep": 0.20, "confidence": 0.10},
            "final_raw": r4(timing_score),
            "final_rounded": round(timing_score),
        },
        "chain": {
            "bands": {
                "leg": [leg_band[0]["frame"], leg_band[-1]["frame"]] if leg_band else None,
                "trunk_arm": [trunk_arm_band[0]["frame"], trunk_arm_band[-1]["frame"]] if trunk_arm_band else None,
                "wrist": [wrist_band[0]["frame"], wrist_band[-1]["frame"]] if wrist_band else None,
            },
            "energy": {
                "leg": r4(leg_energy),
                "trunk": r4(trunk_energy),
                "elbow": r4(elbow_energy),
                "wrist": r4(wrist_energy),
            },
            "energy_scores": {
                "leg": r4(leg_energy_score),
                "trunk": r4(trunk_energy_score),
                "elbow": r4(elbow_energy_score),
                "wrist": r4(wrist_energy_score),
                "combined": r4(energy_score),
            },
            "centers": {
                "leg": r4(leg_center),
                "trunk": r4(trunk_center),
                "elbow": r4(elbow_center),
                "wrist": r4(wrist_center),
            },
            "order_pair_scores": order_pair_breakdown,
            "order_score": r4(order_score),
            "wrist_peak_frame": wrist_peak_frame,
            "wrist_late_score": r4(wrist_late_score),
            "knee_load_deg": r4(max(knee_bends) if knee_bends else 0.0),
            "knee_load_score": r4(knee_load_score),
            "confidence_score": r4(confidence_score),
            "weights": {"energy": 0.32, "order": 0.26, "wrist_late": 0.18, "knee_load": 0.14, "confidence": 0.10},
            "final_raw": r4(chain_score),
            "final_rounded": round(chain_score),
        },
        "recovery": {
            "recover_window": [recover_start, recover_end],
            "stable_frame": stable_frame,
            "recovery_seconds": r4(recovery_seconds),
            "recovery_time_score": r4(recovery_time_score),
            "residual_motion": r4(residual_motion),
            "residual_score": r4(residual_score),
            "posture_median_deg": r4(posture_median),
            "posture_score": r4(posture_score),
            "weights": {"recovery_time": 0.54, "residual": 0.28, "posture": 0.18},
            "final_raw": r4(recovery_score),
            "final_rounded": round(recovery_score),
        },
    }

    return {
        "timing_score": round(timing_score),
        "chain_score": round(chain_score),
        "recovery_score": round(recovery_score),
        "score_breakdown": score_breakdown,
        "contact_height_body": round(contact_height_ratio, 2),
        "max_contact_height_body": round(max_height_ratio, 2),
        "recovery_time_sec": round(recovery_seconds, 2),
        "peak_order_frames": {
            "knee": knee_peak_frame,
            "torso": twist_peak_frame,
            "elbow": elbow_peak_frame,
            "wrist": wrist_peak_frame,
        },
        "chain_energy": {
            "leg": round(leg_energy, 1),
            "trunk": round(trunk_energy, 1),
            "elbow": round(elbow_energy, 1),
            "wrist": round(wrist_energy, 2),
            "energy_score": round(energy_score),
            "order_score": round(order_score),
        },
        "score_reliability": {
            "timing": reliability("timing"),
            "chain": reliability("chain"),
            "recovery": reliability("recovery"),
        },
        "reliability_notes": notes,
        "low_confidence_ratio": round(low_conf_ratio, 3),
    }


def enrich_realtime_metrics(records, events, fps):
    prev_elbow = None
    prev_knee = None
    for rec in records:
        elbow = rec.get("elbow_angle_deg")
        knee = rec.get("knee_angle_deg")
        elbow_speed = abs(elbow - prev_elbow) * fps if elbow is not None and prev_elbow is not None else 0.0
        knee_speed = abs(knee - prev_knee) * fps if knee is not None and prev_knee is not None else 0.0
        prev_elbow = elbow if elbow is not None else prev_elbow
        prev_knee = knee if knee is not None else prev_knee

        torso = max(float(rec.get("torso_length_px") or 70.0), 55.0)
        normalized_wrist = float(rec.get("wrist_speed_px_s") or 0.0) / torso
        phase_boost = {
            "ready": 0.45,
            "backswing": 0.70,
            "drive": 1.05,
            "contact": 1.18,
            "follow": 0.82,
            "recover": 0.50,
        }.get(rec.get("phase"), 0.7)
        rec["elbow_angular_speed_deg_s"] = round(float(elbow_speed), 1)
        rec["knee_angular_speed_deg_s"] = round(float(knee_speed), 1)
        rec["normalized_wrist_speed_body_s"] = round(float(normalized_wrist), 2)

    stroke_metrics = []
    for event_idx in events:
        start = max(0, int(event_idx - fps * 1.05))
        end = min(len(records) - 1, int(event_idx + fps * 0.10))
        window = records[start : end + 1]
        if not window:
            continue
        wrist_vals = [float(r.get("normalized_wrist_speed_body_s") or 0.0) for r in window]
        elbow_vals = [float(r.get("elbow_angular_speed_deg_s") or 0.0) for r in window]
        wrist_peak = float(np.percentile(wrist_vals, 90)) if wrist_vals else 0.0
        elbow_peak = float(np.percentile(elbow_vals, 85)) if elbow_vals else 0.0
        knee_bends = [max(0.0, 180.0 - float(r["knee_angle_deg"])) for r in window if r.get("knee_angle_deg") is not None]
        knee_bend = max(knee_bends) if knee_bends else 0.0
        contact = records[event_idx]
        quality = stroke_quality_scores(records, event_idx, start, end, fps)
        motion_intensity = float(np.clip(wrist_peak * 2.2 + min(elbow_peak, 1800.0) * 0.015 + knee_bend * 0.15, 0.0, 100.0))
        stroke_metrics.append({
            "event_frame": event_idx,
            "event_type": "power_action",
            "event_label": "重发力窗口",
            "window": [start, end],
            "active_side": contact.get("active_side"),
            "phase_label": "重发力评分",
            "timing_score": quality["timing_score"],
            "chain_score": quality["chain_score"],
            "recovery_score": quality["recovery_score"],
            "score_breakdown": quality["score_breakdown"],
            "score_reliability": quality["score_reliability"],
            "reliability_notes": quality["reliability_notes"],
            "contact_height_body": quality["contact_height_body"],
            "max_contact_height_body": quality["max_contact_height_body"],
            "recovery_time_sec": quality["recovery_time_sec"],
            "peak_order_frames": quality["peak_order_frames"],
            "chain_energy": quality["chain_energy"],
            "low_confidence_ratio": quality["low_confidence_ratio"],
            "motion_intensity_index": round(motion_intensity, 1),
            "wrist_whip_speed_body_s": round(wrist_peak, 2),
            "elbow_whip_speed_deg_s": round(elbow_peak, 1),
            "knee_bend_deg": round(knee_bend, 1),
        })

    if not stroke_metrics:
        for rec in records:
            rec["stroke_index"] = None
            rec["stroke_metrics"] = {
                "timing_score": 0,
                "chain_score": 0,
                "recovery_score": 0,
                "score_reliability": {"timing": "低", "chain": "低", "recovery": "低"},
                "motion_intensity_index": 0.0,
                "wrist_whip_speed_body_s": 0.0,
                "elbow_whip_speed_deg_s": 0.0,
                "knee_bend_deg": 0.0,
            }
        return records, stroke_metrics

    for i, stroke in enumerate(stroke_metrics):
        seg_start = 0 if i == 0 else stroke["event_frame"]
        seg_end = (stroke_metrics[i + 1]["event_frame"] - 1) if i + 1 < len(stroke_metrics) else len(records) - 1
        frame_stroke = {k: v for k, v in stroke.items() if k != "score_breakdown"}
        for frame_idx in range(seg_start, seg_end + 1):
            records[frame_idx]["stroke_index"] = i
            records[frame_idx]["stroke_metrics"] = frame_stroke
    return records, stroke_metrics


def draw_skeleton(frame, rec, raw_frame):
    pose = raw_frame.get("pose") or []
    phase = rec["phase"]
    color = PHASE_COLORS.get(phase, (160, 160, 160))
    overlay = frame.copy()
    for a, b in POSE_CONNECTIONS:
        if a >= len(pose) or b >= len(pose) or pose[a] is None or pose[b] is None:
            continue
        va = float(pose[a].get("visibility", 1.0))
        vb = float(pose[b].get("visibility", 1.0))
        if min(va, vb) < 0.18:
            continue
        pa = tuple(np.round([pose[a]["px"], pose[a]["py"]]).astype(int))
        pb = tuple(np.round([pose[b]["px"], pose[b]["py"]]).astype(int))
        cv2.line(overlay, pa, pb, color, 6, cv2.LINE_AA)
        cv2.line(frame, pa, pb, (20, 24, 32), 2, cv2.LINE_AA)
    cv2.addWeighted(overlay, 0.70, frame, 0.30, 0, frame)
    for idx, p in enumerate(pose):
        if p is None:
            continue
        vis = float(p.get("visibility", 1.0))
        if vis < 0.18:
            continue
        center = tuple(np.round([p["px"], p["py"]]).astype(int))
        radius = 7 if idx in {13, 14, 15, 16} else 5
        cv2.circle(frame, center, radius + 2, (20, 24, 32), -1, cv2.LINE_AA)
        cv2.circle(frame, center, radius, (255, 90, 210), -1, cv2.LINE_AA)


def draw_panel(frame, rec, event_index=None):
    h, w = frame.shape[:2]
    cv2.rectangle(frame, (0, 0), (w, 86), (12, 18, 28), -1)
    phase = rec["phase"]
    color = PHASE_COLORS.get(phase, (160, 160, 160))
    phase_label = {"ready": "ready", "backswing": "backswing", "drive": "drive", "contact": "power", "follow": "follow", "recover": "recover"}.get(phase, phase)
    stroke = rec.get("stroke_metrics") or {}
    text = f"2D review | phase={phase_label} | scores describe heavy action windows"
    cv2.putText(frame, text, (18, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.68, color, 2, cv2.LINE_AA)
    metric_text = (
        f"timing={stroke.get('timing_score', 0):.0f}/100 | "
        f"chain={stroke.get('chain_score', 0):.0f}/100 | "
        f"recovery={stroke.get('recovery_score', 0):.0f}/100"
    )
    cv2.putText(frame, metric_text, (18, 62), cv2.FONT_HERSHEY_SIMPLEX, 0.58, (235, 240, 245), 2, cv2.LINE_AA)
    if event_index is not None:
        cv2.rectangle(frame, (w - 210, 14), (w - 18, 70), (45, 45, 75), -1)
        cv2.putText(frame, f"Power #{event_index + 1}", (w - 188, 50), cv2.FONT_HERSHEY_SIMPLEX, 0.82, (255, 255, 120), 2, cv2.LINE_AA)


def build_video(video_path, raw_frames, records, events, out_dir, label, fps):
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise RuntimeError(str(video_path))
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    out = out_dir / f"{label}_2d_review_overlay.mp4"
    writer = subprocess.Popen(
        [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "bgr24",
            "-s", f"{width}x{height}", "-r", str(fps), "-i", "-",
            "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out),
        ],
        stdin=subprocess.PIPE,
    )
    idx = 0
    while True:
        ok, frame = cap.read()
        if not ok or idx >= len(records):
            break
        draw_skeleton(frame, records[idx], raw_frames[idx])
        draw_panel(frame, records[idx], None)
        writer.stdin.write(frame.tobytes())
        idx += 1
    cap.release()
    writer.stdin.close()
    if writer.wait() != 0:
        raise RuntimeError(f"ffmpeg failed to encode {out}")
    return out


def prepare_web_video(video_path, out_dir, label, fps):
    out = out_dir / f"{label}_review_source.mp4"
    if out.exists() and out.stat().st_mtime >= Path(video_path).stat().st_mtime:
        return out
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(video_path),
            "-an",
            "-vf",
            f"fps={fps:.6f},format=yuv420p",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-movflags",
            "+faststart",
            str(out),
        ],
        check=True,
    )
    return out


def write_html(payload, report_path, video_name, overlay_name):
    html = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>2D Badminton Action Review</title>
  <style>
    :root {
      color-scheme: dark;
      --bg:#08110f;
      --surface:#0d1917;
      --panel:#13231f;
      --court:#0f7a4c;
      --court2:#1fbf75;
      --line:#29483f;
      --line2:#d7f8df;
      --text:#f2fff7;
      --muted:#9cb7ad;
      --accent:#9df2c2;
      --speed:#f6d56a;
      --warn:#ff7d74;
      --blue:#7bd8ff;
      --pink:#ff7bd4;
    }
    * { box-sizing: border-box; }
    body {
      margin:0;
      min-height:100vh;
      background:
        linear-gradient(90deg, transparent 49.7%, rgba(215,248,223,.08) 49.8%, rgba(215,248,223,.08) 50.2%, transparent 50.3%),
        linear-gradient(180deg, rgba(31,191,117,.14), transparent 38%),
        var(--bg);
      color:var(--text);
      font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
    }
    .landing {
      position:relative;
      min-height:100vh;
      overflow:hidden;
      display:grid;
      place-items:center;
      padding:28px;
      background:
        linear-gradient(90deg, transparent 49.65%, rgba(215,248,223,.18) 49.8%, rgba(215,248,223,.18) 50.2%, transparent 50.35%),
        linear-gradient(0deg, transparent 18%, rgba(215,248,223,.13) 18.2%, rgba(215,248,223,.13) 18.55%, transparent 18.8%, transparent 81%, rgba(215,248,223,.13) 81.2%, rgba(215,248,223,.13) 81.55%, transparent 81.8%),
        linear-gradient(90deg, rgba(8,17,15,.96), rgba(15,122,76,.30) 46%, rgba(8,17,15,.96)),
        var(--bg);
    }
    .landing::before {
      content:"";
      position:absolute;
      inset:6% 18%;
      border:2px solid rgba(215,248,223,.20);
      border-radius:10px;
      box-shadow:inset 0 0 0 1px rgba(31,191,117,.12), 0 0 80px rgba(31,191,117,.08);
      pointer-events:none;
    }
    .landingCard {
      position:relative;
      z-index:2;
      width:min(720px, 92vw);
      display:grid;
      gap:24px;
      justify-items:center;
      text-align:center;
    }
    .landingTitle { margin:0; font-size:clamp(42px, 7vw, 86px); line-height:.94; font-weight:850; color:var(--text); text-shadow:0 0 24px rgba(157,242,194,.18); }
    .landingMark { font-size:42px; filter:drop-shadow(0 0 14px rgba(157,242,194,.32)); }
    .uploadHint { margin:0; color:var(--muted); font-size:13px; line-height:1.5; }
    .uploadDock {
      width:min(620px, 100%);
      display:grid;
      grid-template-columns:minmax(0,1fr) 158px;
      gap:10px;
      padding:10px;
      border:1px solid rgba(157,242,194,.34);
      border-radius:8px;
      background:rgba(8,17,15,.82);
      box-shadow:0 24px 70px rgba(0,0,0,.25);
    }
    .uploadLabel {
      min-height:54px;
      display:flex;
      align-items:center;
      justify-content:center;
      padding:0 16px;
      border:1px dashed rgba(215,248,223,.34);
      border-radius:6px;
      color:#d9f7e7;
      background:rgba(13,25,23,.78);
      cursor:pointer;
      overflow:hidden;
      white-space:nowrap;
      text-overflow:ellipsis;
      font-weight:650;
    }
    .uploadLabel input { display:none; }
    .startReview {
      min-height:54px;
      border:0;
      border-radius:6px;
      background:linear-gradient(135deg, var(--court2), #f6d56a);
      color:#06120e;
      font-weight:850;
      font-size:16px;
      cursor:pointer;
      box-shadow:0 10px 30px rgba(31,191,117,.22);
    }
    .startReview:disabled { cursor:not-allowed; filter:saturate(.45); opacity:.55; box-shadow:none; }
    .racketDecor {
      position:absolute;
      left:max(18px, 5vw);
      bottom:8vh;
      width:160px;
      height:330px;
      transform:rotate(-24deg);
      opacity:.66;
      pointer-events:none;
    }
    .racketDecor::before {
      content:"";
      position:absolute;
      left:24px;
      top:0;
      width:112px;
      height:154px;
      border:9px solid rgba(215,248,223,.78);
      border-radius:54% 54% 46% 46%;
      background:
        linear-gradient(90deg, transparent 47%, rgba(215,248,223,.32) 48%, rgba(215,248,223,.32) 52%, transparent 53%),
        repeating-linear-gradient(90deg, transparent 0 13px, rgba(215,248,223,.18) 14px 15px),
        repeating-linear-gradient(0deg, transparent 0 13px, rgba(215,248,223,.18) 14px 15px);
    }
    .racketDecor::after {
      content:"";
      position:absolute;
      left:73px;
      top:145px;
      width:18px;
      height:178px;
      border-radius:999px;
      background:linear-gradient(180deg, rgba(215,248,223,.85), rgba(246,213,106,.85));
      box-shadow:0 0 0 5px rgba(8,17,15,.45);
    }
    .shuttleDecor {
      position:absolute;
      right:max(18px, 5vw);
      top:14vh;
      width:220px;
      height:360px;
      pointer-events:none;
      opacity:.82;
    }
    .shuttleDecor span {
      position:absolute;
      font-size:42px;
      filter:drop-shadow(0 0 12px rgba(157,242,194,.18));
    }
    .shuttleDecor span:nth-child(1){ right:8px; top:0; transform:rotate(18deg); }
    .shuttleDecor span:nth-child(2){ right:112px; top:118px; transform:rotate(-26deg) scale(.86); opacity:.78; }
    .shuttleDecor span:nth-child(3){ right:34px; top:238px; transform:rotate(38deg) scale(.72); opacity:.70; }
    .app { min-height:100vh; padding:14px; display:grid; grid-template-rows:auto 1fr; gap:12px; }
    .app[hidden], .landing[hidden] { display:none; }
    .topbar { display:flex; align-items:center; gap:12px; min-height:46px; padding:0 4px; }
    .brand { display:flex; align-items:center; gap:10px; min-width:0; }
    .mark { width:30px; height:30px; display:grid; place-items:center; font-size:24px; line-height:1; flex:0 0 auto; filter:drop-shadow(0 0 10px rgba(157,242,194,.28)); }
    .brand h1 { margin:0; font-size:20px; letter-spacing:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    h2 { margin:0; font-size:14px; color:#cde4d8; font-weight:650; }
    .chip { border:1px solid var(--line); background:#0b1714; color:#cde4d8; border-radius:999px; padding:6px 10px; font-size:12px; line-height:1; text-decoration:none; }
    main { min-width:0; display:grid; grid-template-columns:minmax(0,1fr) 370px; grid-template-rows:auto auto auto; gap:12px; align-content:start; }
    .videoPanel, .actionPanel, .timelinePanel, .detailsPanel { min-width:0; border:1px solid var(--line); background:rgba(13,25,23,.94); border-radius:8px; }
    .videoPanel { padding:10px; }
    .panelHead { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:8px; }
    video { width:100%; background:#05070a; border:1px solid rgba(215,248,223,.32); border-radius:6px; }
    a { color:var(--accent); }
    .player { position:relative; width:100%; }
    .player video { display:block; }
    #poseCanvas { position:absolute; inset:0; width:100%; height:100%; pointer-events:none; }
    .actionPanel { padding:12px; display:flex; flex-direction:column; gap:10px; }
    .stageCard { border:1px solid rgba(157,242,194,.5); border-radius:8px; padding:12px; background:linear-gradient(135deg, rgba(15,122,76,.45), rgba(8,17,15,.92)); }
    .stageCard span, .live > span, .metric span { display:block; color:var(--muted); font-size:12px; margin-bottom:7px; }
    .stageCard strong { display:block; color:#fff8c4; font-size:30px; line-height:1.05; }
    .strokeLine { display:flex; align-items:center; justify-content:space-between; gap:8px; color:#cde4d8; font-size:13px; }
    .liveGrid { display:grid; grid-template-columns:1fr; gap:12px; }
    .live {
      --scoreColor:var(--accent);
      border:1px solid rgba(157,242,194,.34);
      border-radius:8px;
      padding:17px 18px 15px;
      background:
        linear-gradient(90deg, rgba(215,248,223,.045) 1px, transparent 1px) 0 0/42px 100%,
        linear-gradient(135deg, rgba(15,122,76,.20), rgba(7,17,15,.98));
      min-height:132px;
      display:flex;
      flex-direction:column;
      justify-content:space-between;
      box-shadow:inset 0 1px 0 rgba(215,248,223,.05);
    }
    .live > span { color:#cfe6db; font-size:15px; font-weight:700; margin:0; }
    .scoreRow { display:flex; align-items:flex-end; gap:8px; margin-top:6px; }
    .live strong { display:flex; align-items:flex-end; gap:7px; color:var(--scoreColor); line-height:.86; font-variant-numeric:tabular-nums; letter-spacing:0; }
    .scoreValue { display:inline-block; min-width:2ch; font-size:64px; font-weight:800; letter-spacing:0; }
    .unit { display:inline-block; color:var(--muted); font-size:20px; font-weight:700; line-height:1; padding-bottom:5px; margin:0; }
    .scoreBar { height:8px; border-radius:999px; overflow:hidden; background:rgba(215,248,223,.10); border:1px solid rgba(215,248,223,.12); margin-top:14px; }
    .scoreBar i { display:block; width:0%; height:100%; border-radius:999px; background:linear-gradient(90deg, var(--scoreColor), rgba(242,255,247,.88)); box-shadow:0 0 14px rgba(157,242,194,.22); transition:width .16s ease; }
    .live.timing { --scoreColor:var(--speed); }
    .live.chain { --scoreColor:var(--blue); }
    .live.recovery { --scoreColor:#b9f4ce; }
    .note { color:var(--muted); font-size:12px; line-height:1.45; margin:0; }
    .timelinePanel { grid-column:1 / -1; padding:10px; }
    #timeline { width:100%; height:86px; min-height:64px; border:1px solid var(--line); border-radius:6px; background:#07110f; display:block; touch-action:none; cursor:pointer; }
    .legend { display:flex; flex-wrap:wrap; gap:7px; margin-top:8px; }
    .legend span { display:inline-flex; align-items:center; gap:5px; color:#cde4d8; font-size:12px; }
    .dot { width:9px; height:9px; border-radius:50%; display:inline-block; }
    .detailsPanel { grid-column:1 / -1; padding:10px 12px; }
    details summary { cursor:pointer; color:#cde4d8; font-size:13px; min-height:32px; display:flex; align-items:center; }
    .metrics { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; margin-top:10px; }
    .metric { border:1px solid var(--line); border-radius:6px; padding:10px; background:#0b1714; }
    .metric strong { font-size:18px; }
    table { width:100%; border-collapse:collapse; font-size:13px; }
    th,td { border-bottom:1px solid var(--line); padding:7px 6px; text-align:left; }
    th { color:#cbd5e1; font-weight:600; }
    @media(max-width:960px){
      .app { padding:10px; }
      .topbar { align-items:flex-start; }
      main { grid-template-columns:1fr; }
      .liveGrid { grid-template-columns:repeat(3,minmax(0,1fr)); gap:8px; }
      .stageCard { padding:10px; }
      .stageCard strong { font-size:28px; }
      .live { min-height:116px; padding:14px; }
      .scoreValue { font-size:48px; }
      .unit { font-size:16px; padding-bottom:4px; }
      .metrics { grid-template-columns:1fr; }
    }
    @media(max-width:620px){
      .topbar { flex-direction:column; align-items:stretch; }
      .chip { padding:6px 8px; }
      .actionPanel { gap:8px; padding:10px; }
      .stageCard { padding:8px; }
      .stageCard strong { font-size:24px; }
      .strokeLine { font-size:12px; }
      .liveGrid { grid-template-columns:1fr; }
      .live { min-height:104px; padding:12px; }
      .live > span { font-size:13px; }
      .scoreValue { font-size:44px; }
      .unit { font-size:15px; }
      .actionPanel .note { display:none; }
      #timeline { height:74px; }
    }
  </style>
</head>
<body>
<section id="landing" class="landing">
  <div class="racketDecor" aria-hidden="true"></div>
  <div class="shuttleDecor" aria-hidden="true"><span>🏸</span><span>🏸</span><span>🏸</span></div>
  <div class="landingCard">
    <div class="landingMark" aria-hidden="true">🏸</div>
    <h1 class="landingTitle">ShuttlePoseReview</h1>
    <div class="uploadDock">
      <label class="uploadLabel"><input id="uploadVideo" type="file" accept="video/*"><span id="uploadName">上传视频</span></label>
      <button id="startReview" class="startReview" disabled>开始复盘</button>
    </div>
    <p class="uploadHint">当前版本会进入已完成分析的复盘工作台；真正上传后重新分析需要接入视频处理流程。</p>
  </div>
</section>
<div id="reviewApp" class="app" hidden>
  <header class="topbar">
    <div class="brand"><span class="mark" aria-hidden="true">🏸</span><h1>ShuttlePoseReview</h1></div>
  </header>
  <main>
    <section class="videoPanel">
      <div class="panelHead">
        <h2>视频 + 2D 骨架</h2>
        <span class="chip">动作复盘</span>
      </div>
      <div class="player">
        <video id="sourceVideo" src="__VIDEO__" controls muted playsinline preload="auto"></video>
        <canvas id="poseCanvas"></canvas>
      </div>
    </section>
    <aside class="actionPanel">
      <h2>当前动作</h2>
      <div class="stageCard"><span>阶段</span><strong id="phaseNow">-</strong></div>
      <div class="strokeLine"><span id="strokeNow">第 - 次重发力</span><span id="timeNow">0.00s</span></div>
      <div class="liveGrid">
        <div class="live timing"><span>击球时机</span><div class="scoreRow"><strong><span id="timingScoreNow" class="scoreValue">-</span><span class="unit">/100</span></strong></div><div class="scoreBar"><i id="timingBar"></i></div></div>
        <div class="live chain"><span>发力链</span><div class="scoreRow"><strong><span id="chainScoreNow" class="scoreValue">-</span><span class="unit">/100</span></strong></div><div class="scoreBar"><i id="chainBar"></i></div></div>
        <div class="live recovery"><span>回位恢复</span><div class="scoreRow"><strong><span id="recoveryScoreNow" class="scoreValue">-</span><span class="unit">/100</span></strong></div><div class="scoreBar"><i id="recoveryBar"></i></div></div>
      </div>
      <p class="note">三项分数来自明显重发力窗口，并保持到下一次重发力。放网、轻挡、过渡球可能不会单独计数；这里不是全部击球次数。</p>
    </aside>
    <section class="timelinePanel">
      <div class="panelHead"><h2>动作时间轴</h2><span class="chip">点击或拖动跳转</span></div>
      <canvas id="timeline" width="1200" height="96"></canvas>
      <div class="legend">
        <span><i class="dot" style="background:#667085"></i>准备</span>
        <span><i class="dot" style="background:#f6b84d"></i>引拍</span>
        <span><i class="dot" style="background:#59d7ff"></i>蹬转</span>
        <span><i class="dot" style="background:#ff6961"></i>发力点</span>
        <span><i class="dot" style="background:#c084fc"></i>随挥</span>
        <span><i class="dot" style="background:#42d392"></i>回位</span>
      </div>
    </section>
    <section class="detailsPanel">
      <details>
        <summary>分析信息</summary>
        <div class="metrics">
          <div class="metric"><span>Pose 覆盖率</span><strong id="poseCoverage"></strong></div>
          <div class="metric"><span>分析模式</span><strong>Pose only</strong></div>
          <div class="metric"><span>指标口径</span><strong>重发力窗口 0-100</strong></div>
        </div>
        <table>
          <thead><tr><th>核心分数</th><th>当前口径</th></tr></thead>
          <tbody>
            <tr><td>击球时机</td><td>依赖重发力窗口、手腕相对肩部高度、发力点肘角和准备期侧身代理；适合判断低点、靠后、准备晚。</td></tr>
            <tr><td>发力链</td><td>依赖击球前后膝部加载、躯干打开、肘部加速、手腕鞭打的时间窗口能量；比单纯峰值顺序更稳。</td></tr>
            <tr><td>回位恢复</td><td>依赖重发力后手腕速度、肘膝角速度和躯干回正；适合看打完能否接下一拍，不适合判断真实步法距离。</td></tr>
          </tbody>
        </table>
      </details>
    </section>
  </main>
</div>
<script id="review-data" type="application/json">__DATA__</script>
<script>
const data = JSON.parse(document.getElementById('review-data').textContent);
const src = document.getElementById('sourceVideo'), poseCanvas = document.getElementById('poseCanvas'), pctx = poseCanvas.getContext('2d');
const timeline = document.getElementById('timeline'), tctx = timeline.getContext('2d');
const landing = document.getElementById('landing'), reviewApp = document.getElementById('reviewApp');
const uploadInput = document.getElementById('uploadVideo'), uploadName = document.getElementById('uploadName'), startReview = document.getElementById('startReview');
const phaseColors = {ready:'#667085', backswing:'#f6b84d', drive:'#59d7ff', contact:'#ff6961', follow:'#c084fc', recover:'#42d392'};
const phaseLabels = {ready:'准备', backswing:'引拍', drive:'蹬转加速', contact:'发力点', follow:'随挥', recover:'回位'};
document.getElementById('poseCoverage').textContent = (data.summary.pose_coverage*100).toFixed(1)+'%';
let selectedFrame = 0;
let seekToken = 0;
function cancelPendingSeek(){ seekToken += 1; }
function setVideoTime(video, t){
  const token = ++seekToken;
  const apply=()=>{ if(token !== seekToken) return; if(video.readyState > 0){ try { video.currentTime = t; } catch(e) {} } };
  apply();
  video.addEventListener('loadedmetadata', apply, { once:true });
  video.addEventListener('loadeddata', apply, { once:true });
  video.addEventListener('canplay', apply, { once:true });
  setTimeout(apply,120);
  setTimeout(apply,500);
  setTimeout(apply,1100);
}
function seekFrame(frame){ selectedFrame = Math.max(0, Math.min(data.records.length - 1, frame)); const t=data.records[selectedFrame].time_sec; setVideoTime(src,t); drawAll(selectedFrame); }
function drawTimeline(active=0){
  tctx.clearRect(0,0,timeline.width,timeline.height);
  const n=data.records.length, w=timeline.width, h=timeline.height, bandY=22, bandH=34;
  data.records.forEach((r,i)=>{ tctx.fillStyle=phaseColors[r.phase]||'#94a3b8'; const x=i/(n-1)*w; tctx.globalAlpha=.82; tctx.fillRect(x,bandY,Math.ceil(w/n)+1,bandH); });
  tctx.globalAlpha=1;
  tctx.fillStyle='#9cb7ad'; tctx.font='12px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif';
  const duration=data.records[data.records.length-1].time_sec || 0;
  const tickStep=duration>16?5:2;
  for(let t=0;t<=duration+.01;t+=tickStep){ const x=t/duration*w; tctx.fillRect(x,62,1,8); tctx.fillText(Math.round(t)+'s',Math.min(w-26,x+4),78); }
  for(const ev of data.summary.event_frames || []){ const x=ev/(n-1)*w; tctx.fillStyle='#f2fff7'; tctx.fillRect(x-1,13,2,50); tctx.beginPath(); tctx.arc(x,13,5,0,Math.PI*2); tctx.fill(); }
  const x=active/(n-1)*w; tctx.fillStyle='#fff8c4'; tctx.fillRect(x-2,6,4,68);
}
function fmtScore(v){ return v===null || v===undefined || !Number.isFinite(Number(v)) ? '-' : Math.round(Number(v)); }
function setScore(id, barId, value){ const n=Number(value); const ok=Number.isFinite(n); document.getElementById(id).textContent = ok ? Math.round(n) : '-'; document.getElementById(barId).style.width = ok ? Math.max(0, Math.min(100, n))+'%' : '0%'; }
function drawInfo(frame){ const r=data.records[frame], s=r.stroke_metrics || {}; const phase=phaseLabels[r.phase] || r.phase; const stroke=(r.stroke_index ?? 0)+1; document.getElementById('phaseNow').textContent = phase; document.getElementById('strokeNow').textContent = '第 '+stroke+' 次重发力'; document.getElementById('timeNow').textContent = Number(r.time_sec || 0).toFixed(2)+'s'; setScore('timingScoreNow','timingBar',s.timing_score); setScore('chainScoreNow','chainBar',s.chain_score); setScore('recoveryScoreNow','recoveryBar',s.recovery_score); }
function resizePoseCanvas(){ if(!src.videoWidth || !src.videoHeight) return; poseCanvas.width = src.videoWidth; poseCanvas.height = src.videoHeight; }
function drawPoseOverlay(frame){ resizePoseCanvas(); pctx.clearRect(0,0,poseCanvas.width,poseCanvas.height); const r=data.records[frame], pose=r.pose2d || []; const color=phaseColors[r.phase] || '#94a3b8'; pctx.lineCap='round'; pctx.lineJoin='round'; for(const [a,b] of data.pose_connections){ const A=pose[a], B=pose[b]; if(!A||!B||Math.min(A[2],B[2])<0.18) continue; pctx.strokeStyle=color; pctx.lineWidth=6; pctx.beginPath(); pctx.moveTo(A[0],A[1]); pctx.lineTo(B[0],B[1]); pctx.stroke(); } pose.forEach((p,i)=>{ if(!p||p[2]<0.18) return; const key=[13,14,15,16,25,26].includes(i); pctx.fillStyle=key?'#f6d56a':'#f2fff7'; const rad=key?7:5; pctx.beginPath(); pctx.arc(p[0],p[1],rad,0,Math.PI*2); pctx.fill(); }); }
function drawAll(frame){ drawTimeline(frame); drawInfo(frame); drawPoseOverlay(frame); }
let lastUiFrame = -1;
function drawPlaybackFrame(frame){
  drawPoseOverlay(frame);
  const current = data.records[frame] || {};
  const previous = data.records[lastUiFrame] || {};
  const shouldUpdateUi = lastUiFrame < 0 || Math.abs(frame - lastUiFrame) >= 3 || current.phase !== previous.phase || current.stroke_index !== previous.stroke_index;
  if(shouldUpdateUi){
    lastUiFrame = frame;
    drawTimeline(frame);
    drawInfo(frame);
  }
}
function frameFromMediaTime(mediaTime){
  return Math.min(data.records.length-1, Math.max(0, Math.round(Number(mediaTime || 0)*data.summary.fps)));
}
function frameFromPointer(e){ const r=timeline.getBoundingClientRect(); return Math.round((e.clientX-r.left)/r.width*(data.records.length-1)); }
let scrubbing=false;
timeline.addEventListener('pointerdown', e=>{ scrubbing=true; timeline.setPointerCapture(e.pointerId); src.pause(); seekFrame(frameFromPointer(e)); });
timeline.addEventListener('pointermove', e=>{ if(scrubbing) seekFrame(frameFromPointer(e)); });
timeline.addEventListener('pointerup', e=>{ scrubbing=false; timeline.releasePointerCapture(e.pointerId); });
timeline.addEventListener('pointercancel', ()=>{ scrubbing=false; });
let initializing = true, lastDrawnFrame = -1;
function drawSynced(mediaTime){
  if(initializing || scrubbing) return;
  const frame = frameFromMediaTime(mediaTime);
  selectedFrame = frame;
  if(frame!==lastDrawnFrame){
    lastDrawnFrame=frame;
    drawPlaybackFrame(frame);
  }
}
function animationLoop(){
  drawSynced(src.currentTime);
  requestAnimationFrame(animationLoop);
}
function videoFrameLoop(now, metadata){
  const mediaTime = metadata && Number.isFinite(metadata.mediaTime) ? metadata.mediaTime : src.currentTime;
  drawSynced(mediaTime);
  src.requestVideoFrameCallback(videoFrameLoop);
}
if('requestVideoFrameCallback' in HTMLVideoElement.prototype){
  src.requestVideoFrameCallback(videoFrameLoop);
} else {
  requestAnimationFrame(animationLoop);
}
src.addEventListener('play', () => { cancelPendingSeek(); src.playbackRate = 1; });
src.addEventListener('seeking', cancelPendingSeek);
src.addEventListener('seeked', () => { const frame=frameFromMediaTime(src.currentTime); selectedFrame=frame; lastDrawnFrame=frame; drawAll(frame); });
window.addEventListener('resize',()=>{ const frame=frameFromMediaTime(src.currentTime); drawPoseOverlay(frame); });
const requestedFrame = Number(new URLSearchParams(location.search).get('frame'));
const initialFrame = Number.isFinite(requestedFrame) ? Math.max(0, Math.min(data.records.length - 1, Math.round(requestedFrame))) : 0;
selectedFrame = initialFrame;
drawAll(initialFrame);
setVideoTime(src, data.records[initialFrame].time_sec);
setTimeout(() => { initializing = false; drawAll(selectedFrame); }, 900);
uploadInput.addEventListener('change', () => {
  const file = uploadInput.files && uploadInput.files[0];
  uploadName.textContent = file ? file.name : '上传视频';
  startReview.disabled = !file;
});
startReview.addEventListener('click', () => {
  landing.hidden = true;
  reviewApp.hidden = false;
  initializing = true;
  selectedFrame = 0;
  drawAll(0);
  setVideoTime(src, data.records[0].time_sec || 0);
  setTimeout(() => { initializing = false; drawAll(0); }, 900);
});
</script>
</body>
</html>"""
    report_path.write_text(
        html.replace("__VIDEO__", video_name)
        .replace("__OVERLAY__", overlay_name)
        .replace("__DATA__", json.dumps(payload, ensure_ascii=False)),
        encoding="utf-8",
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--landmarks", required=True)
    parser.add_argument("--metrics", required=True)
    parser.add_argument("--video", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--label", default="img5868")
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    raw = json.loads(Path(args.landmarks).read_text(encoding="utf-8"))
    metrics = json.loads(Path(args.metrics).read_text(encoding="utf-8"))
    fps = float(metrics.get("fps") or 30.0)

    left_wrist = smooth_series([point(f, 15) if visibility(f, 15) >= 0.12 else None for f in raw], alpha=0.62)
    right_wrist = smooth_series([point(f, 16) if visibility(f, 16) >= 0.12 else None for f in raw], alpha=0.62)
    left_speed = speed(left_wrist, fps)
    right_speed = speed(right_wrist, fps)
    event_scores = action_scores(raw, left_speed, right_speed)
    events = robust_events(event_scores, fps)

    records = [frame_metrics(frame, fps, idx, left_speed, right_speed, events) for idx, frame in enumerate(raw)]
    records, stroke_metrics = enrich_realtime_metrics(records, events, fps)
    event_summaries = [summarize_event(records, idx, fps) for idx in events]

    video_src = Path(args.video).resolve()
    video_dst = out_dir / video_src.name
    if not video_dst.exists() or video_dst.stat().st_size != video_src.stat().st_size:
        shutil.copy2(video_src, video_dst)
    web_video = prepare_web_video(video_src, out_dir, args.label, fps)
    overlay = build_video(video_src, raw, records, events, out_dir, args.label, fps)

    summary = {
        "label": args.label,
        "frames": len(records),
        "fps": fps,
        "duration_sec": round(len(records) / fps, 3) if fps else None,
        "pose_coverage": metrics.get("pose_coverage", 1.0),
        "event_frames": events,
        "event_semantics": "明显重发力窗口，不代表全部击球次数；放网、轻挡、过渡球可能不会单独计数。",
        "power_action_frames": events,
        "power_action_count": len(events),
        "outputs": {
            "report_html": str(out_dir / f"{args.label}_2d_action_review.html"),
            "review_video": str(web_video),
            "overlay_video": str(overlay),
            "review_json": str(out_dir / f"{args.label}_2d_action_review.json"),
        },
    }
    payload = {"summary": summary, "records": records, "events": event_summaries, "stroke_metrics": stroke_metrics, "pose_connections": POSE_CONNECTIONS}
    json_path = out_dir / f"{args.label}_2d_action_review.json"
    report_path = out_dir / f"{args.label}_2d_action_review.html"
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    write_html(payload, report_path, f"{web_video.name}?v={web_video.stat().st_mtime_ns}", f"{overlay.name}?v={overlay.stat().st_mtime_ns}")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
