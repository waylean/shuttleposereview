# ShuttlePoseReview

ShuttlePoseReview 是一个面向羽毛球爱好者的动作复盘项目。它从普通手机录制的视频中提取人体 2D 骨架，识别明显重发力窗口，并把每次重发力动作拆解为三个可解释指标：`击球时机`、`发力链`、`回位恢复`。

项目目标是帮助业余球友更清楚地看到：这一拍有没有打到更合适的位置，发力是否从身体传到手腕，打完以后是否能及时回到下一拍准备状态。

## 当前能力

- 上传一段羽毛球视频，生成本地复盘任务。
- Android 端可直接从手机相册选择视频，在本机完成抽帧、姿态识别和复盘。
- Android 端当前支持处理视频前 60 秒，适合训练后一段连续回合的快速复盘。
- 对视频做预处理，转为更适合浏览器和算法处理的 MP4。
- 使用人体姿态模型提取近端球员的 2D 骨架。
- 在视频上叠加骨架，生成可播放的动作复盘页面。
- 支持导出已经叠加姿态骨架的 MP4 视频，便于保存、分享或二次剪辑。
- 已复盘过的视频会在本地缓存结果，主页最多保留最近 20 条历史复盘，可直接翻阅查看。
- 自动识别明显重发力窗口，而不是把所有放网、轻挡、过渡球都计为一次重发力。
- 为每个重发力窗口输出三项分数、综合质量参考和可展开证据卡。
- 输出结构化 JSON，方便后续做训练记录、横向对比和算法评估。

## 1.2 版本 Android 更新公告

这一版主要补齐 Android 端的复盘闭环：从相册选择视频、等待端上分析、进入正式复盘主页面，再到慢动作回看、证据卡展开和历史记录翻阅。软件仍然只基于端上姿态规则做保守判断，不把单次动作复盘包装成真实水平评级。

- **相册选择视频**：Android 端优先打开系统相册/视频选择器，选择体验更接近日常使用；不支持时会回退到系统文件选择器。
- **60 秒视频处理**：Android 端会处理导入视频的前 60 秒，更适合连续回合、训练片段和多拍对抗复盘。
- **复盘主页面落地**：处理完成后直接进入主页面，集中展示骨架叠加视频、重发力状态、三项分数和时间线。
- **重发力慢动作**：播放经过重发力窗口时会自动慢放，也可以点击时间线直接跳到某一次重发力附近。
- **慢动作可调节**：Android 端支持 `0.75x / 0.50x / 0.33x` 三档速度，并可一键关闭慢动作。
- **骨架播放更顺滑**：骨架覆盖层加入前后姿态帧插值，低速回看时减少骨架跳帧感。
- **历史复盘**：主页新增历史入口，最多保留最近 20 条本机复盘记录；在限制内上传过多少条，都可以直接翻阅旧结果。
- **证据卡可展开**：击球时机、发力链、回位恢复三项都可以展开查看，底层数据会被整理为档位、解释、置信度和训练建议。
- **弱化等级表达**：不再强调外部等级标签，改为“综合质量参考”和 `S / A / B / C / D` 档位，避免把单次动作复盘误读成真实水平评定。

## Android APK 下载

推荐优先从 GitHub Releases 下载最新 APK：

```text
https://github.com/waylean/shuttleposereview/releases
```

Android 构建方式：

```bash
cd apps/android
./gradlew :app:assembleDebug
```

Windows PowerShell：

```powershell
cd apps/android
.\gradlew.bat :app:assembleDebug
```

安装后，点击“选择或导入视频”，等待复盘完成即可在结果页查看骨架叠加视频、重发力时间线和三项动作指标。结果页底部的“下载姿态合成视频”可以导出带骨架的 MP4。


## 实机演示

![ShuttlePoseReview 实机演示](assets/demo/badminton_review_demo.gif)

演示视频文件：

```text
assets/demo/badminton_review_demo.mp4
```

## 适用视频

为了让结果更稳定，建议输入视频满足以下条件：

- 视频建议控制在 60 秒内，最好是一段连续回合或一个连续多拍片段。
- 如果直接上传更长的视频，Android 端当前会优先处理前 60 秒。
- 近端球员需要清晰可见，身体、手臂和脚步不要长期被遮挡。
- 手机低视角、侧后方或正后方拍摄都可以尝试，但同一用户做趋势对比时，最好保持相近机位。
- 当前更适合分析杀球、高远球、平抽、重发力过渡等明显挥拍动作；放网、轻挡等小动作可能不会单独计入重发力窗口。

## 工作流程

```text
上传视频
  -> 视频预处理
  -> 人体 2D pose 提取
  -> 近端球员骨架与动作窗口分析
  -> 三项指标计算
  -> 生成复盘页面、叠加视频和 JSON 结果
```

Web MVP 的本地运行说明见：

```text
apps/web/README.md
```

当前主要算法实现：

```text
work/scripts/build_2d_action_review.py
```

完整公式说明：

```text
docs/scoring_formula_spec.md
```

## 三项指标

ShuttlePoseReview 当前不会直接判断“真实球速”或“真实发力大小”。系统先从骨架中计算关节角、速度、相对高度、躯干尺度和动作窗口，再把这些可观测代理变量转化为动作复盘指标。

### 1. 击球时机

击球时机回答的问题是：这一拍是否在更合适的位置和准备状态下完成发力。

核心观察量：

| 子项 | 含义 |
|---|---|
| 手腕高度 | 发力点附近，手腕是否处在更容易完成高点击球的位置 |
| 发力点肘角 | 肘部是否处在较合理的击球伸展区间 |
| 准备期姿态 | 击球前是否有架拍、抬手、侧身或肩髋打开迹象 |
| 主动手臂可见度 | 当前骨架数据是否足够可读 |

公式摘要：

```text
timing_score
  = clamp(
      0.46 * height_score
    + 0.24 * elbow_score
    + 0.20 * prep_score
    + 0.10 * confidence_score
    )
```

其中：

```text
height_score = 0.55 * max_height_score + 0.45 * contact_height_score
elbow_score  = band_score(elbow_angle_at_event; ideal=145°, tolerance=70°)
prep_score   = 0.62 * prep_height_score + 0.38 * twist_score
```

合理性：

- 羽毛球重发力动作通常需要更好的击球高度和提前准备。
- 肘角不是越直越好，而是需要处在一个相对可发力的伸展区间。
- 准备期姿态可以区分“提前架拍发力”和“仓促抡拍”。

### 2. 发力链

发力链回答的问题是：这一拍是否呈现出从下肢、躯干、手臂到手腕的连续加速节奏。

系统不会只看某一帧的最大速度，而是按事件帧 `e` 切出三个时间窗口：

```text
leg_band       = [e - 0.65F, e - 0.22F]
trunk_arm_band = [e - 0.38F, e - 0.06F]
wrist_band     = [e - 0.18F, e + 0.08F]
```

其中 `F` 是视频帧率。

能量代理：

```text
leg_energy   = P80(knee_angular_speed in leg_band)
trunk_energy = P80(abs(twist_t - twist_{t-1}) * F in trunk_arm_band)
elbow_energy = P82(elbow_angular_speed in trunk_arm_band)
wrist_energy = P88(normalized_wrist_speed in wrist_band)
```

综合公式：

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

- `energy_score` 判断这一拍是否真的有明显动作能量。
- `order_score` 判断能量中心是否大体从下肢、躯干、肘部传到手腕。
- `wrist_late_score` 判断手腕速度峰值是否贴近重发力事件帧。
- `knee_load_score` 用击球前屈膝程度作为下肢参与的简化代理。

### 3. 回位恢复

回位恢复回答的问题是：这一拍打完后，身体是否能较快回到可以衔接下一拍的状态。

计算窗口：

```text
recover = [e + 0.12F, e + 1.20F]
```

稳定帧条件：

```text
normalized_wrist_speed <= 1.15
elbow_angular_speed    <= 360°/s
knee_angular_speed     <= 300°/s
```

综合公式：

```text
recovery_score
  = clamp(
      0.54 * recovery_time_score
    + 0.28 * residual_score
    + 0.18 * posture_score
    )
```

合理性：

- 恢复时间越短，说明打完后越快回到可控制状态。
- 残余动作越小，说明随挥后身体没有继续大幅散开。
- 肩髋分离角回落，说明身体姿态更接近下一拍准备状态。

## 重要边界

当前指标更适合：

- 同一用户、同一机位、相似动作之间做趋势对比。
- 帮助业余球友发现动作问题方向。
- 为训练复盘提供结构化参考。

当前指标不适合：

- 直接比较不同机位、不同距离、不同人的绝对水平。
- 测得真实羽毛球速度。
- 恢复出真实 3D 发力链或真实肌肉输出。

## 运行 Web MVP

当前 Web MVP 使用 Python 虚拟环境运行：

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python -m uvicorn apps.web.main:app --host 127.0.0.1 --port 8787
```

打开：

```text
http://127.0.0.1:8787
```

如果端口被占用，可以换成：

```bash
python -m uvicorn apps.web.main:app --host 127.0.0.1 --port 8788
```

系统还需要可用的 `ffmpeg` 命令，用于视频预处理。

## 输出文件

一次分析通常会生成：

```text
*_2d_action_review.html    # 交互式复盘页面
*_2d_review_overlay.mp4    # 骨架叠加视频
*_2d_action_review.json    # 结构化分析结果
```

每次重发力的完整公式证据位于：

```text
stroke_metrics[].score_breakdown
```

这使得每个总分都可以追溯到具体的窗口、角度、速度、子分数和权重。


## 致谢

本项目受到多个开源项目和工具的启发与支持，包括但不限于：

- Good-Badminton：提供了羽毛球视频分析方向的重要启发。
- Va6lue/BST-Badminton-Stroke-type-Transformer（BST/BTS）：提供了从骨架序列理解羽毛球动作类型的参考思路。
- MediaPipe：用于人体姿态和骨架点位提取。
- OpenCV：用于视频处理、帧读取和骨架叠加。
- FFmpeg：用于视频预处理和格式转换。
- FastAPI / Uvicorn：用于本地 Web MVP 的上传和任务服务。
