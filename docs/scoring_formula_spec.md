# ShuttlePoseReview 三项指标计算公式与合理性说明

版本：V2  
对应实现：`work/scripts/build_2d_action_review.py`  
适用范围：普通手机单机位 2D pose 复盘，近端球员清晰可见，连续回合或连续重发力片段。

## 摘要

本文给出 ShuttlePoseReview 当前三项指标的精确定义：

1. `击球时机`：从发力点附近的手腕高度、肘角、准备期姿态和骨架可见度计算。
2. `发力链`：从发力点前后的下肢、躯干、肘部、手腕时间窗口能量及能量传递顺序计算。
3. `回位恢复`：从发力后 1.2 秒内的稳定时间、残余动作和姿态回正计算。

这些指标不是羽毛球真实速度、真实肌肉力、真实 3D 生物力学参数，而是基于单机位 2D pose 的动作复盘代理指标。它们的合理性来自运动动作的可观测代理变量：高点击球、准备姿态、动能链时序、随挥后稳定性。

## 1. 输入数据定义

对视频第 `t` 帧，pose 模型输出人体关键点：

```text
P_t(j) = (x_tj, y_tj, v_tj)
```

其中：

- `x_tj, y_tj` 是第 `j` 个关键点在视频像素坐标系中的位置。
- `v_tj` 是该关键点的可见度或置信度。
- `fps = F` 是视频帧率。

当前使用的关键点：

| 关键点 | MediaPipe Pose 编号 |
|---|---:|
| 左肩 / 右肩 | 11 / 12 |
| 左肘 / 右肘 | 13 / 14 |
| 左腕 / 右腕 | 15 / 16 |
| 左髋 / 右髋 | 23 / 24 |
| 左膝 / 右膝 | 25 / 26 |
| 左踝 / 右踝 | 27 / 28 |

坐标系约定：

- 视频坐标 `y` 向下增加。
- 因此手腕高于肩部时，`shoulder_y - wrist_y > 0`。

## 2. 基础几何量

### 2.1 指数平滑后的手腕轨迹

为了降低逐帧 pose 抖动，事件检测中的左右手腕轨迹先做指数平滑：

```text
W'_t = (1 - α) W'_{t-1} + α W_t
α = 0.62
```

如果当前帧手腕点缺失，则沿用上一帧平滑值。

### 2.2 手腕速度

```text
speed_t = F * || W'_t - W'_{t-1} ||_2
```

单位是 `px/s`。这是像素速度，不是物理速度。

### 2.3 身体尺度归一化

不同视频中人物远近不同，所以将速度除以躯干长度：

```text
shoulder_center_t = (P_t(11) + P_t(12)) / 2
hip_center_t      = (P_t(23) + P_t(24)) / 2
torso_t           = || shoulder_center_t - hip_center_t ||_2
torso_t           = max(torso_t, 55)
normalized_wrist_speed_t = wrist_speed_t / torso_t
```

在单机位 2D 视频中，这一步不能消除所有透视误差，但能明显减少分辨率、裁剪大小、人物远近造成的直接尺度差异。

### 2.4 关节角

三点夹角定义为：

```text
angle(a, b, c) = arccos( ((a-b) · (c-b)) / (||a-b|| ||c-b||) )
```

当前使用：

```text
elbow_angle_t = angle(shoulder, elbow, wrist)
knee_angle_t  = angle(hip, knee, ankle)
```

角速度使用相邻帧差分：

```text
elbow_angular_speed_t = F * | elbow_angle_t - elbow_angle_{t-1} |
knee_angular_speed_t  = F * | knee_angle_t  - knee_angle_{t-1}  |
```

### 2.5 躯干打开代理

肩线角度：

```text
shoulder_line_t = atan2(y_12 - y_11, x_12 - x_11)
```

髋线角度：

```text
hip_line_t = atan2(y_24 - y_23, x_24 - x_23)
```

肩髋分离角：

```text
twist_t = abs_wrap_180(shoulder_line_t - hip_line_t)
```

其中 `abs_wrap_180` 将角度差映射到 `[-180°, 180°]` 后取绝对值。

这不是严格 3D 躯干旋转，而是 2D 画面中的躯干打开代理。

## 3. 评分函数

所有子分数最终映射到 `[0, 100]`。

### 3.1 线性区间评分

```text
range_score(x; low, high) = clamp(100 * (x - low) / (high - low), 0, 100)
```

如果需要递减评分，可以直接让 `high < low`。例如恢复时间越小越好：

```text
range_score(recovery_seconds; 1.20, 0.28)
```

也就是：

```text
recovery_seconds = 1.20 -> 0分
recovery_seconds = 0.28 -> 100分
```

### 3.2 理想区间评分

```text
band_score(x; ideal, tolerance)
  = clamp(100 - |x - ideal| / tolerance * 55, 0, 100)
```

当前肘角使用：

```text
band_score(elbow_angle; ideal=145°, tolerance=70°)
```

也就是肘角越接近 `145°` 得分越高，偏离越多扣分。

## 4. 明显重发力窗口检测

### 4.1 单侧候选强度

对左右手分别计算：

```text
height_t = shoulder_y_t - wrist_y_t
height_bonus_t = 1 + max(0, height_t) / 50
low_height_penalty_t = max(0, -height_t) * 8
conf_t = min(shoulder_visibility, elbow_visibility, wrist_visibility)
conf_floor_t = 0.65 + min(0.35, max(0, conf_t) * 0.35)

side_action_score_t
  = max(0, wrist_speed_t * height_bonus_t * conf_floor_t - low_height_penalty_t)
```

左右两侧取最大值：

```text
action_score_t = max(left_action_score_t, right_action_score_t)
```

### 4.2 峰值筛选

阈值：

```text
threshold = max(P86(action_score), 0.38 * max(action_score))
```

候选点满足：

```text
action_score_t >= threshold
action_score_t >= action_score_{t-1}
action_score_t >= action_score_{t+1}
```

相邻事件最小间隔：

```text
gap = max(14 frames, 0.75 * F frames)
```

候选点按强度从高到低选取，最多保留 6 个，然后按时间排序。

### 4.3 合理性

羽毛球重发力动作通常具有：

- 主动手腕速度上升。
- 手腕在肩部附近或肩部上方。
- 相邻重发力动作之间不会无限密集。

因此事件检测不是“识别全部击球”，而是找适合做发力复盘的动作峰值。放网、轻挡、过渡球可能真实击球，但不会被这个检测器稳定计为重发力窗口。

## 5. 击球时机公式

对一个重发力事件帧 `e`，定义：

```text
start = e - 1.05F
contact_end = e + 0.10F
stroke_window = [start, contact_end]
pre = [start, e]
```

躯干尺度：

```text
L = max(median(torso_t in stroke_window), 45)
```

主动手臂可见度：

```text
arm_conf = median(active_arm_confidence_t in stroke_window)
confidence_score = clamp((arm_conf - 0.25) / 0.65 * 100, 0, 100)
```

### 5.1 手腕高度分

```text
contact_height_ratio = wrist_above_shoulder_e / L
max_height_ratio = max(wrist_above_shoulder_t in stroke_window) / L

max_height_score     = range_score(max_height_ratio; -0.15, 0.95)
contact_height_score = range_score(contact_height_ratio; -0.35, 0.75)

height_score = 0.55 * max_height_score + 0.45 * contact_height_score
```

含义：

- `max_height_ratio` 看整个窗口是否具备高点动作。
- `contact_height_ratio` 看发力点本身是否仍在合理高度。
- 两者结合，避免只因为某一帧手腕高但真正发力点偏低而过度加分。

### 5.2 肘角分

```text
elbow_score = band_score(elbow_angle_e; ideal=145°, tolerance=70°)
```

含义：

- 发力点肘部应有一定伸展，但不是越直越好。
- 当前用宽容度 `70°`，是因为普通 2D 视频视角差异较大，不能使用过窄标准。

### 5.3 准备期姿态分

准备期截断点：

```text
prep_cut = e - 0.38F
prep = [start, prep_cut]
```

```text
prep_height_ratio = max(wrist_above_shoulder_t / L in prep)
prep_height_score = range_score(prep_height_ratio; -0.35, 0.80)

max_twist = max(shoulder_hip_separation_t in pre)
twist_score = range_score(max_twist; 4°, 24°)

prep_score = 0.62 * prep_height_score + 0.38 * twist_score
```

含义：

- `prep_height_score` 表示击球前是否有架拍或抬手准备。
- `twist_score` 表示击球前是否出现侧身/肩髋打开迹象。

### 5.4 最终击球时机分

```text
timing_score
  = clamp(
      0.46 * height_score
    + 0.24 * elbow_score
    + 0.20 * prep_score
    + 0.10 * confidence_score
    )
```

合理性：

- 高点击球和发力点位置是主因素，因此手腕高度权重最高。
- 肘角反映击球时手臂是否处于可发力状态。
- 准备期姿态用于区分“提前准备”与“仓促抡拍”。
- 置信度只占 10%，避免视频遮挡直接决定动作分数，但仍对低质量骨架做惩罚。

## 6. 发力链公式

发力链不直接估计真实力，而是计算不同身体环节在时间上的运动能量代理。

### 6.1 时间窗口

以事件帧 `e` 为中心：

```text
leg_band       = [e - 0.65F, e - 0.22F]
trunk_arm_band = [e - 0.38F, e - 0.06F]
wrist_band     = [e - 0.18F, e + 0.08F]
```

这些窗口对应：

- 下肢加载/释放应更早出现。
- 躯干打开和肘部加速出现在中段。
- 手腕鞭打接近发力点。

### 6.2 能量代理

```text
leg_energy   = P80(knee_angular_speed_t in leg_band)
trunk_energy = P80(|twist_t - twist_{t-1}| * F in trunk_arm_band)
elbow_energy = P82(elbow_angular_speed_t in trunk_arm_band)
wrist_energy = P88(normalized_wrist_speed_t in wrist_band)
```

使用分位数而不是最大值，是为了减少单帧 pose 抖动造成的尖峰误判。

### 6.3 能量分映射

```text
leg_energy_score   = range_score(leg_energy;   90,  520)
trunk_energy_score = range_score(trunk_energy; 35,  260)
elbow_energy_score = range_score(elbow_energy; 220, 1350)
wrist_energy_score = range_score(wrist_energy; 2.2, 10.5)
```

综合能量：

```text
energy_score
  = clamp(
      0.20 * leg_energy_score
    + 0.18 * trunk_energy_score
    + 0.24 * elbow_energy_score
    + 0.38 * wrist_energy_score
    )
```

手腕权重最高，是因为在普通单机位视频里，挥拍末端速度是最稳定、最直观的重发力观测量。下肢和躯干受拍摄角度影响更大，因此权重较低。

### 6.4 能量中心与顺序分

对某个窗口内的非负能量序列 `q_t`，定义能量中心：

```text
center = sum(t * q_t) / sum(q_t)
```

当前计算：

```text
leg_center
trunk_center
elbow_center
wrist_center
```

顺序对：

```text
(leg_center, trunk_center)
(trunk_center, elbow_center)
(elbow_center, wrist_center)
```

每一对的时间差：

```text
diff_sec = (downstream_center - upstream_center) / F
pair_score = range_score(diff_sec; -0.08, 0.22)
```

最终：

```text
order_score = mean(pair_score)
```

合理性：

- 不要求每个峰值严格逐帧排序，因为 2D pose 抖动会让峰值点漂移。
- 只要求下游环节的能量中心“大体晚于”上游环节。
- 允许 `-0.08s` 的容差，避免少量帧误差导致发力链被完全否定。

### 6.5 手腕峰值贴近发力点

```text
wrist_peak_frame = argmax(normalized_wrist_speed in [start, e+0.10F])
wrist_late_score = range_score(|wrist_peak_frame - e| / F; 0.38, 0.02)
```

含义：

- 手腕速度峰值越靠近重发力点越好。
- 如果手腕峰值远早于或远晚于发力点，说明检测点和挥拍节奏可能错位。

### 6.6 膝部加载分

膝部弯曲量：

```text
knee_bend_t = max(0, 180° - knee_angle_t)
knee_load = max(knee_bend_t in pre)
knee_load_score = range_score(knee_load; 12°, 72°)
```

含义：

- 击球前一定程度的屈膝可作为下肢参与发力的代理。
- 这不是说膝盖越弯越好，而是在当前普通视频场景下用作简化指标。

### 6.7 最终发力链分

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

合理性：

- `energy_score` 表示是否真的有明显动作能量。
- `order_score` 表示能量是否按合理节奏向末端传递。
- `wrist_late_score` 保证手腕爆发接近击球窗口。
- `knee_load_score` 鼓励下肢参与，但不让它主导总分。
- `confidence_score` 仍作为骨架质量修正。

## 7. 回位恢复公式

回位恢复从事件后约 1.2 秒内计算：

```text
recover = [e + 0.12F, e + 1.20F]
```

### 7.1 稳定帧

第一个满足以下条件的帧记为 `stable_frame`：

```text
normalized_wrist_speed_t <= 1.15
elbow_angular_speed_t    <= 360°/s
knee_angular_speed_t     <= 300°/s
```

如果找到稳定帧：

```text
recovery_seconds = (stable_frame - e) / F
recovery_time_score = range_score(recovery_seconds; 1.20, 0.28)
```

如果找不到稳定帧：

```text
recovery_seconds = (recover_end - e) / F
recovery_time_score = 32
```

### 7.2 残余动作分

```text
residual_motion = median(normalized_wrist_speed_t in recover)
residual_score = range_score(residual_motion; 1.70, 0.35)
```

### 7.3 姿态回正分

```text
posture = median(shoulder_hip_separation_t in recover)
posture_score = range_score(posture; 26°, 5°)
```

### 7.4 最终回位恢复分

```text
recovery_score
  = clamp(
      0.54 * recovery_time_score
    + 0.28 * residual_score
    + 0.18 * posture_score
    )
```

合理性：

- 回位首先看恢复速度，所以 `recovery_time_score` 权重最高。
- 如果打完后手腕仍高速运动，说明随挥或身体控制还没收住。
- 肩髋分离角回落代表身体更接近可准备下一拍的稳定姿态。

## 8. IMG_5870 第 5 次重发力计算例

样例文件：

```text
outputs/scoring_formula_img5870_v2/img5870_v2_event5_formula_breakdown.md
outputs/scoring_formula_img5870_v2/img5870_v2_formula_breakdown.json
```

第 5 次重发力：

```text
event_frame = 503
time_sec = 16.773
calculation_window = [471, 505]
```

公共量：

```text
torso_median_px = 131.90
arm_conf_median = 0.7810
confidence_score = 81.6923
low_confidence_ratio = 0.343
```

### 8.1 击球时机

```text
contact_height_ratio = 0.6892
max_height_ratio = 0.9257
max_height_score = 97.7910
contact_height_score = 94.4690
height_score = 96.2961
contact_elbow_angle = 87.3°
elbow_score = 54.6643
prep_height_ratio = 0.2782
prep_height_score = 54.6297
max_twist = 40.7°
twist_score = 100.0000
prep_score = 71.8704
confidence_score = 81.6923
```

代入：

```text
timing_score
= 0.46*96.2961 + 0.24*54.6643 + 0.20*71.8704 + 0.10*81.6923
= 79.9589
= 80 / 100 after round
```

解释：这拍手腕高度很好，所以 `height_score` 高；但发力点肘角只有 `87.3°`，离当前理想代理角 `145°` 较远，因此 `elbow_score` 拉低总分。

### 8.2 发力链

时间窗口：

```text
leg_band       = [484, 496]
trunk_arm_band = [492, 501]
wrist_band     = [498, 505]
```

能量：

```text
leg_energy   = 820.5
trunk_energy = 490.6
elbow_energy = 3636.5
wrist_energy = 14.24
```

能量映射后：

```text
leg_energy_score   = 100
trunk_energy_score = 100
elbow_energy_score = 100
wrist_energy_score = 100
energy_score       = 100
```

能量中心：

```text
leg_center   = 492.8654
trunk_center = 497.4221
elbow_center = 496.9897
wrist_center = 501.5191
```

顺序对：

```text
leg -> trunk:
  diff_sec = 0.1519
  pair_score = 77.3154

trunk -> elbow:
  diff_sec = -0.0144
  pair_score = 21.8610

elbow -> wrist:
  diff_sec = 0.1510
  pair_score = 77.0124

order_score = 58.7296
```

第 5 次重发力的其它子项：

```text
wrist_peak_frame = 502
wrist_late_score = 96.2928
knee_load_score ≈ 100
confidence_score = 81.6923
```

代入：

```text
chain_score
= 0.32*100 + 0.26*58.7296 + 0.18*96.2928 + 0.14*100 + 0.10*81.6923
= 86.7716
= 87 / 100 after round
```

解释：这拍身体各环节的运动能量很强，手腕速度峰值也贴近事件帧，所以 `energy_score` 和 `wrist_late_score` 都高；扣分主要来自躯干到肘部的能量中心轻微倒序，使 `order_score` 只有 `58.7296`。

### 8.3 回位恢复

```text
recover_window = [506, 538]
stable_frame = 516
recovery_seconds = 0.4335
recovery_time_score = 83.3155
residual_motion = 2.1400
residual_score = 0.0000
posture_median = 8.4°
posture_score = 83.8095
```

代入：

```text
recovery_score
= 0.54*83.3155 + 0.28*0 + 0.18*83.8095
= 60.0761
= 60 / 100 after round
```

解释：这拍很快出现低速稳定帧，姿态也较快回正，所以两个子项较高；但恢复窗口内手腕归一化速度中位数仍高于当前阈值上界，`residual_score=0`，说明随挥后的末端动作还没有完全收住。

## 9. 严格性与局限

### 9.1 当前已经严格的部分

当前实现是确定性的：

- 同一份 pose JSON 输入会得到同一组事件和分数。
- 每个分数都可由上述公式复现。
- 没有使用语言模型主观判断分数。
- 没有声称真实球速或真实力学功率。

### 9.2 当前还不够严格的部分

当前权重和阈值是启发式设计，不是大样本监督学习拟合结果：

- `0.46/0.24/0.20/0.10` 等权重来自产品可解释性和动作常识，并非来自标注数据训练。
- `145°` 肘角理想值、`0.28s-1.20s` 恢复时间区间等阈值需要更多样本校准。
- 2D 单机位无法完全解决侧身遮挡、透视变形和远端肢体缺失。

因此更严谨的对外表述应为：

> 三项分数是基于 2D 骨架的动作复盘指数，用于同一用户、同一机位、相似动作之间的趋势对比。它不是医学级、教练级或 3D 生物力学结论。

## 10. 工程状态

当前已经把所有子分数写入 JSON：

```text
timing.height_score
timing.elbow_score
timing.prep_score
timing.confidence_score

chain.energy_score
chain.order_score
chain.wrist_late_score
chain.knee_load_score
chain.confidence_score

recovery.recovery_time_score
recovery.residual_score
recovery.posture_score
```

对应字段位于：

```text
stroke_metrics[].score_breakdown
```

为了避免页面文件过大，每一帧的 `records[].stroke_metrics` 仍只保存展示所需的轻量字段；完整公式证据只保存在顶层 `stroke_metrics` 中。
