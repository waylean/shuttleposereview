package com.waylean.shuttleposereview.ondevice;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Landmark;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.io.Serializable;
import java.security.MessageDigest;

public class MainActivity extends Activity {
    private static final String TAG = "ShuttlePoseReview";
    private static final String ACTION_RUN_FILE = "com.waylean.shuttleposereview.ondevice.RUN_FILE";
    private static final String EXTRA_VIDEO_PATH = "video_path";
    private static final int PICK_VIDEO = 1001;
    private static final int SHORT_TARGET_FPS = 15;
    private static final int LONG_TARGET_FPS = 10;
    private static final int TARGET_FPS = SHORT_TARGET_FPS;
    private static final int MAX_DURATION_MS = 60_000;
    private static final int LONG_MAX_DURATION_MS = 30 * 60_000;
    private static final int MAX_HISTORY_REVIEWS = 20;
    private static final int MODEL_INPUT_MAX_WIDTH = 640;
    private static final int LONG_MODEL_INPUT_MAX_WIDTH = 512;
    private static final String CACHE_SCHEMA_VERSION = "pose-fit-v5-mode-60s-longpose";
    private static final int COLOR_BG = 0xffF7F8F5;
    private static final int COLOR_SURFACE = 0xffffffff;
    private static final int COLOR_TEXT = 0xff161A17;
    private static final int COLOR_MUTED = 0xff68716A;
    private static final int COLOR_PRIMARY = 0xff127A5B;
    private static final int COLOR_PRIMARY_DARK = 0xff0B3D31;
    private static final int COLOR_BORDER = 0xffE2E7DE;
    private static final int COLOR_ACCENT = 0xffF2A93B;

    private TextView output;
    private ProgressBar progress;
    private Button pickButton;
    private Button demoButton;
    private Button exportButton;
    private Button shortModeButton;
    private Button longModeButton;
    private Button slowMotionToggleButton;
    private Button slowMotionSpeedButton;
    private LinearLayout progressCard;
    private LinearLayout resultCard;
    private TextView progressTitle;
    private TextView progressBody;
    private TextView scoreSummary;
    private TextView performanceSummary;
    private TextView eventSummary;
    private View uploadScreen;
    private View processingScreen;
    private View dashboardScreen;
    private VideoView reviewVideo;
    private PoseOverlayView poseOverlay;
    private TextView selectedFileLabel;
    private TextView eventCountText;
    private TextView strokeStatusTitle;
    private TextView strokeStatusTime;
    private TextView timingValue;
    private TextView chainValue;
    private TextView recoveryValue;
    private TextView actionLevelValue;
    private TextView evidenceDetail;
    private LinearLayout timelineStrip;
    private LinearLayout historyPanel;
    private LinearLayout historyList;
    private TextView historyHint;
    private CachedReview currentReview;
    private StrokeScore selectedStroke;
    private String expandedEvidenceType = "";
    private Runnable playbackSpeedMonitor;
    private Runnable initialPreviewPauseRunnable;
    private android.media.MediaPlayer reviewMediaPlayer;
    private boolean slowMotionEnabled = true;
    private float slowMotionSpeed = 0.5f;
    private float appliedPlaybackSpeed = 1.0f;
    private boolean longVideoMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(buildPcFlowView());
        handleLaunchIntent();
        uploadScreen.post(this::loadLastReviewIfAvailable);
        if (SystemClock.uptimeMillis() >= 0) {
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(0xffF7F8F5);

        TextView title = new TextView(this);
        title.setText("ShuttlePoseReview On-device");
        title.setTextSize(24);
        title.setTextColor(0xff0B3D31);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(false);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText("选择一段羽毛球视频，手机本地完成抽帧、MediaPipe Pose 推理和三项评分。默认最多处理前 60 秒，15fps 抽样。");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xff68716A);
        subtitle.setPadding(0, dp(8), 0, dp(14));
        root.addView(subtitle);

        pickButton = new Button(this);
        pickButton.setText("选择视频并端上计算");
        pickButton.setAllCaps(false);
        pickButton.setOnClickListener(v -> pickVideo());
        root.addView(pickButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
        ));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(12)
        );
        progressParams.setMargins(0, dp(14), 0, dp(14));
        root.addView(progress, progressParams);

        output = new TextView(this);
        output.setText("等待选择视频。");
        output.setTextSize(14);
        output.setTextColor(0xff161A17);
        output.setTextIsSelectable(true);
        output.setLineSpacing(0, 1.08f);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        setContentView(root);

        if (ACTION_RUN_FILE.equals(getIntent().getAction())) {
            String path = getIntent().getStringExtra(EXTRA_VIDEO_PATH);
            if (!TextUtils.isEmpty(path)) {
                pickButton.post(() -> runFile(path));
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent();
    }

    private View buildPcFlowView() {
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(0xff06120F);

        uploadScreen = buildUploadScreen();
        processingScreen = buildProcessingScreen();
        dashboardScreen = buildDashboardScreen();

        host.addView(uploadScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(processingScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(dashboardScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showScreen(uploadScreen);
        return host;
    }

    private View buildUploadScreen() {
        ScrollView scroll = darkScroll();
        LinearLayout content = darkContent();
        scroll.addView(content);
        addTopbar(content, false);

        LinearLayout hero = darkPanel();
        hero.addView(text("把打球视频变成动作复盘", 30, 0xffF2FFF7, Typeface.BOLD));
        TextView intro = text("上传手机录制的视频，手机本地完成抽帧、姿态识别、动作窗口检测，并生成可查看的复盘工作台。", 15, 0xffA9C7BB, Typeface.NORMAL);
        intro.setLineSpacing(dp(3), 1.0f);
        intro.setPadding(0, dp(14), 0, dp(18));
        hero.addView(intro);
        content.addView(hero);

        LinearLayout uploader = darkPanel();
        TextView dropIcon = text("🏸", 46, 0xffF7D76E, Typeface.NORMAL);
        dropIcon.setGravity(Gravity.CENTER);
        uploader.addView(dropIcon);
        selectedFileLabel = text("选择或导入视频", 22, 0xffF2FFF7, Typeface.BOLD);
        selectedFileLabel.setGravity(Gravity.CENTER);
        selectedFileLabel.setPadding(0, dp(6), 0, dp(4));
        uploader.addView(selectedFileLabel);
        TextView hint = text("短视频复盘用于 60 秒内动作分析；长视频骨架只生成全程骨架标注。", 13, 0xffA9C7BB, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(8), 0, dp(8), dp(16));
        uploader.addView(hint);
        LinearLayout modeRow = row();
        shortModeButton = darkButton("短视频复盘\n60秒内分析", false);
        shortModeButton.setOnClickListener(v -> {
            longVideoMode = false;
            updateReviewModeButtons();
        });
        modeRow.addView(shortModeButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        longModeButton = darkButton("长视频骨架\n只渲染骨架", false);
        longModeButton.setOnClickListener(v -> {
            longVideoMode = true;
            updateReviewModeButtons();
        });
        LinearLayout.LayoutParams longModeParams = new LinearLayout.LayoutParams(0, dp(58), 1);
        longModeParams.setMargins(dp(8), 0, 0, 0);
        modeRow.addView(longModeButton, longModeParams);
        uploader.addView(modeRow, bottomMargin(dp(12)));
        updateReviewModeButtons();
        pickButton = darkButton("输入视频并开始复盘", true);
        pickButton.setOnClickListener(v -> pickVideo());
        uploader.addView(pickButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        content.addView(uploader, topMargin(dp(14)));

        historyPanel = darkPanel();
        LinearLayout historyTitle = row();
        historyTitle.addView(text("历史复盘", 22, 0xffF2FFF7, Typeface.BOLD), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView historyLimit = text("最多 " + MAX_HISTORY_REVIEWS + " 条", 13, 0xff93F2C1, Typeface.BOLD);
        historyLimit.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        historyTitle.addView(historyLimit);
        historyPanel.addView(historyTitle);
        historyHint = text("完成过的本机复盘会显示在这里，点击即可继续查看。", 13, 0xffA9C7BB, Typeface.NORMAL);
        historyHint.setLineSpacing(dp(2), 1.0f);
        historyHint.setPadding(0, dp(8), 0, dp(10));
        historyPanel.addView(historyHint);
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        historyPanel.addView(historyList);
        content.addView(historyPanel, topMargin(dp(14)));
        refreshHistoryPanel();

        return scroll;
    }

    private View buildProcessingScreen() {
        ScrollView scroll = darkScroll();
        LinearLayout content = darkContent();
        scroll.addView(content);
        addTopbar(content, false);

        progressCard = darkPanel();
        progressCard.addView(generatedCourtPreview());
        progressTitle = text("排队中", 28, 0xffF2FFF7, Typeface.BOLD);
        progressTitle.setPadding(0, dp(18), 0, dp(8));
        progressCard.addView(progressTitle);
        progressBody = text("等待端上处理开始。", 15, 0xffA9C7BB, Typeface.NORMAL);
        progressBody.setLineSpacing(dp(3), 1.0f);
        progressCard.addView(progressBody);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progressCard.addView(progress, topFixedMargin(dp(18), dp(14)));
        content.addView(progressCard);
        return scroll;
    }

    private View buildDashboardScreen() {
        ScrollView scroll = darkScroll();
        LinearLayout content = darkContent();
        scroll.addView(content);
        addTopbar(content, true);

        resultCard = darkPanel();
        LinearLayout sectionTitle = row();
        sectionTitle.addView(text("动作复盘", 24, 0xffF2FFF7, Typeface.BOLD), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        eventCountText = text("重发力窗口", 13, 0xff93F2C1, Typeface.BOLD);
        eventCountText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        sectionTitle.addView(eventCountText);
        resultCard.addView(sectionTitle);

        FrameLayout videoStage = new FrameLayout(this);
        GradientDrawable videoBg = new GradientDrawable();
        videoBg.setColor(0xff020806);
        videoBg.setCornerRadius(dp(8));
        videoBg.setStroke(dp(1), 0x3320C878);
        videoStage.setBackground(videoBg);
        reviewVideo = new VideoView(this);
        reviewVideo.setBackgroundColor(Color.TRANSPARENT);
        poseOverlay = new PoseOverlayView(this);
        videoStage.addView(reviewVideo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));
        videoStage.addView(poseOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        resultCard.addView(videoStage, topFixedMargin(dp(12), dp(220)));

        LinearLayout statusBar = darkPanel();
        strokeStatusTitle = text("等待复盘结果", 18, 0xffF2FFF7, Typeface.BOLD);
        strokeStatusTime = text("--", 13, 0xffA9C7BB, Typeface.NORMAL);
        statusBar.addView(strokeStatusTitle);
        statusBar.addView(strokeStatusTime, topMargin(dp(4)));
        LinearLayout scoreRow = new LinearLayout(this);
        scoreRow.setOrientation(LinearLayout.HORIZONTAL);
        scoreRow.setGravity(Gravity.CENTER_VERTICAL);
        timingValue = scorePill("击球时机", 0xffF7D76E);
        chainValue = scorePill("发力链", 0xff75D7FF);
        recoveryValue = scorePill("回位恢复", 0xffB9F4CE);
        scoreRow.addView(timingValue, scorePillParams(0));
        scoreRow.addView(chainValue, scorePillParams(dp(8)));
        scoreRow.addView(recoveryValue, scorePillParams(dp(8)));
        statusBar.addView(scoreRow, topMargin(dp(12)));
        actionLevelValue = text("综合质量参考：--", 15, 0xff93F2C1, Typeface.BOLD);
        actionLevelValue.setPadding(0, dp(12), 0, 0);
        statusBar.addView(actionLevelValue);

        LinearLayout evidenceRow = new LinearLayout(this);
        evidenceRow.setOrientation(LinearLayout.HORIZONTAL);
        evidenceRow.setGravity(Gravity.CENTER_VERTICAL);
        evidenceRow.addView(evidenceButton("击球证据", "timing", 0xffF7D76E), scorePillParams(0));
        evidenceRow.addView(evidenceButton("发力证据", "chain", 0xff75D7FF), scorePillParams(dp(8)));
        evidenceRow.addView(evidenceButton("回位证据", "recovery", 0xffB9F4CE), scorePillParams(dp(8)));
        statusBar.addView(evidenceRow, topMargin(dp(12)));
        evidenceDetail = text("", 14, 0xffD7F8DF, Typeface.NORMAL);
        evidenceDetail.setLineSpacing(dp(3), 1.0f);
        evidenceDetail.setPadding(0, dp(10), 0, 0);
        evidenceDetail.setVisibility(View.GONE);
        statusBar.addView(evidenceDetail);
        resultCard.addView(statusBar, topMargin(dp(12)));

        content.addView(resultCard);

        LinearLayout timeline = darkPanel();
        timeline.addView(text("时间线", 22, 0xffF2FFF7, Typeface.BOLD));
        LinearLayout slowControls = row();
        slowMotionToggleButton = darkButton("慢动作：开", false);
        slowMotionToggleButton.setOnClickListener(v -> {
            slowMotionEnabled = !slowMotionEnabled;
            updateSlowMotionControls();
            updateAutoPlaybackSpeed();
        });
        slowControls.addView(slowMotionToggleButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        slowMotionSpeedButton = darkButton("速度：0.5x", false);
        slowMotionSpeedButton.setOnClickListener(v -> {
            cycleSlowMotionSpeed();
            updateSlowMotionControls();
            updateAutoPlaybackSpeed();
        });
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        speedParams.setMargins(dp(8), 0, 0, 0);
        slowControls.addView(slowMotionSpeedButton, speedParams);
        timeline.addView(slowControls, topMargin(dp(12)));
        HorizontalScrollView timelineScroll = new HorizontalScrollView(this);
        timelineScroll.setHorizontalScrollBarEnabled(false);
        timelineStrip = new LinearLayout(this);
        timelineStrip.setOrientation(LinearLayout.HORIZONTAL);
        timelineScroll.addView(timelineStrip);
        timeline.addView(timelineScroll, topMargin(dp(12)));
        eventSummary = text("", 14, 0xffF2FFF7, Typeface.NORMAL);
        eventSummary.setLineSpacing(dp(3), 1.0f);
        timeline.addView(eventSummary, topMargin(dp(12)));
        exportButton = darkButton("下载姿态合成视频", true);
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> exportCurrentReview());
        timeline.addView(exportButton, topFixedMargin(dp(14), dp(54)));
        content.addView(timeline, topMargin(dp(14)));

        LinearLayout files = darkPanel();
        files.addView(text("输出明细", 22, 0xffF2FFF7, Typeface.BOLD));
        output = text("", 13, 0xffA9C7BB, Typeface.NORMAL);
        output.setTextIsSelectable(false);
        output.setLineSpacing(dp(2), 1.0f);
        files.addView(output, topMargin(dp(12)));
        files.setVisibility(View.GONE);
        content.addView(files, topMargin(dp(14)));
        return scroll;
    }

    private void addTopbar(LinearLayout content, boolean withNewReview) {
        LinearLayout topbar = row();
        TextView brand = text("🏸  ShuttlePoseReview", 24, 0xffF2FFF7, Typeface.BOLD);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        topbar.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
        if (withNewReview) {
            Button reset = darkButton("新建复盘", false);
            reset.setOnClickListener(v -> {
                if (reviewVideo != null) {
                    stopPlaybackAutomation();
                    reviewVideo.stopPlayback();
                }
                refreshHistoryPanel();
                showScreen(uploadScreen);
            });
            topbar.addView(reset, new LinearLayout.LayoutParams(dp(116), dp(44)));
        }
        content.addView(topbar);
    }

    private ScrollView darkScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff06120F);
        return scroll;
    }

    private LinearLayout darkContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(24));
        content.setLayoutParams(new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return content;
    }

    private LinearLayout darkPanel() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xee071914);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x38A3F4C8);
        view.setBackground(bg);
        return view;
    }

    private View generatedCourtPreview() {
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.badminton_court_gpt_reference);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff082119);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x3320C878);
        image.setBackground(bg);
        image.setClipToOutline(true);
        image.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        return image;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView scorePill(String label, int color) {
        TextView view = text(label + "\n--", 12, color, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(70));
        view.setLineSpacing(dp(2), 1.0f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff081410);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x2FD7F8DF);
        view.setBackground(bg);
        view.setPadding(dp(6), dp(8), dp(6), dp(8));
        return view;
    }

    private LinearLayout.LayoutParams scorePillParams(int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        );
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
    }

    private Button evidenceButton(String label, String type, int accentColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(accentColor);
        button.setMinHeight(dp(44));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff081410);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), accentColor);
        button.setBackground(bg);
        button.setOnClickListener(v -> toggleEvidence(type));
        return button;
    }

    private Button darkButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(primary ? 18 : 15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? 0xff04120E : 0xff93F2C1);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(primary ? 0xff93F2C1 : 0xff081410);
        bg.setStroke(dp(1), primary ? 0xffF7D76E : 0x33D7F8DF);
        button.setBackground(bg);
        return button;
    }

    private void updateReviewModeButtons() {
        if (shortModeButton != null) {
            shortModeButton.setText(longVideoMode ? "短视频复盘\n60秒内分析" : "✓ 短视频复盘\n60秒内分析");
            styleModeButton(shortModeButton, !longVideoMode);
        }
        if (longModeButton != null) {
            longModeButton.setText(longVideoMode ? "✓ 长视频骨架\n只渲染骨架" : "长视频骨架\n只渲染骨架");
            styleModeButton(longModeButton, longVideoMode);
        }
        if (pickButton != null) {
            pickButton.setText(longVideoMode ? "输入视频并生成骨架" : "输入视频并开始复盘");
        }
    }

    private void styleModeButton(Button button, boolean selected) {
        button.setTextColor(selected ? 0xff04120E : 0xff93F2C1);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(selected ? 0xff93F2C1 : 0xff081410);
        bg.setStroke(dp(1), selected ? 0xffF7D76E : 0x33D7F8DF);
        button.setBackground(bg);
    }

    private LinearLayout.LayoutParams bottomMargin(int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, bottom);
        return params;
    }

    private LinearLayout.LayoutParams topFixedMargin(int top, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.setMargins(0, top, 0, 0);
        return params;
    }

    private void showScreen(View screen) {
        uploadScreen.setVisibility(screen == uploadScreen ? View.VISIBLE : View.GONE);
        processingScreen.setVisibility(screen == processingScreen ? View.VISIBLE : View.GONE);
        dashboardScreen.setVisibility(screen == dashboardScreen ? View.VISIBLE : View.GONE);
        if (screen instanceof ScrollView) {
            screen.post(() -> ((ScrollView) screen).scrollTo(0, 0));
        }
    }

    private View buildMainView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("ShuttlePoseReview", 28, COLOR_PRIMARY_DARK, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        content.addView(title);

        TextView subtitle = text("羽毛球动作复盘 · 手机端本地计算", 15, COLOR_MUTED, Typeface.NORMAL);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        content.addView(subtitle);

        LinearLayout actionCard = card();
        actionCard.addView(text("开始一次复盘", 20, COLOR_TEXT, Typeface.BOLD));
        TextView intro = text("选择一段 60 秒内的训练视频，手机会抽帧、识别姿态并输出击球时机、发力链和回位恢复评分。", 14, COLOR_MUTED, Typeface.NORMAL);
        intro.setLineSpacing(dp(2), 1.0f);
        intro.setPadding(0, dp(8), 0, dp(14));
        actionCard.addView(intro);

        pickButton = styledButton("选择视频", true);
        pickButton.setOnClickListener(v -> pickVideo());
        actionCard.addView(pickButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
        ));

        content.addView(actionCard);

        progressCard = card();
        progressTitle = text("准备就绪", 18, COLOR_TEXT, Typeface.BOLD);
        progressBody = text("等待选择视频。推荐使用球员全身清晰入镜、横屏或竖屏稳定拍摄的视频。", 14, COLOR_MUTED, Typeface.NORMAL);
        progressBody.setPadding(0, dp(8), 0, dp(12));
        progressCard.addView(progressTitle);
        progressCard.addView(progressBody);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progressCard.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(10)
        ));
        content.addView(progressCard, topMargin(dp(12)));

        resultCard = card();
        resultCard.setVisibility(View.GONE);
        resultCard.addView(text("复盘结果", 20, COLOR_TEXT, Typeface.BOLD));
        scoreSummary = text("", 18, COLOR_PRIMARY_DARK, Typeface.BOLD);
        scoreSummary.setPadding(0, dp(12), 0, dp(6));
        resultCard.addView(scoreSummary);
        performanceSummary = text("", 14, COLOR_MUTED, Typeface.NORMAL);
        performanceSummary.setLineSpacing(dp(2), 1.0f);
        resultCard.addView(performanceSummary);
        resultCard.addView(separator(), topMargin(dp(14)));
        eventSummary = text("", 14, COLOR_TEXT, Typeface.NORMAL);
        eventSummary.setLineSpacing(dp(2), 1.0f);
        resultCard.addView(eventSummary, topMargin(dp(12)));
        resultCard.addView(separator(), topMargin(dp(14)));
        output = text("", 13, COLOR_TEXT, Typeface.NORMAL);
        output.setTextIsSelectable(true);
        output.setLineSpacing(dp(1), 1.0f);
        resultCard.addView(output, topMargin(dp(12)));
        content.addView(resultCard, topMargin(dp(12)));

        return scroll;
    }

    private void handleLaunchIntent() {
        if (ACTION_RUN_FILE.equals(getIntent().getAction())) {
            String path = getIntent().getStringExtra(EXTRA_VIDEO_PATH);
            if (!TextUtils.isEmpty(path)) {
                pickButton.post(() -> runFile(path));
            }
        }
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_SURFACE);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), COLOR_BORDER);
        view.setBackground(bg);
        return view;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        return view;
    }

    private Button styledButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(primary ? Color.WHITE : COLOR_PRIMARY_DARK);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? COLOR_PRIMARY : 0xffEEF4EF);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), primary ? COLOR_PRIMARY : 0xffD8E4DA);
        button.setBackground(bg);
        return button;
    }

    private View separator() {
        View line = new View(this);
        line.setBackgroundColor(COLOR_BORDER);
        return line;
    }

    private LinearLayout.LayoutParams topMargin(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, margin, 0, 0);
        return params;
    }

    private void pickVideo() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("video/*");
        } else {
            intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            intent.setType("video/*");
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_VIDEO);
        } catch (Exception pickerError) {
            Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("video/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(fallback, PICK_VIDEO);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VIDEO || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            // Some providers do not offer persistable grants; a one-shot read is enough for this PoC.
        }
        runOnDeviceReview(uri);
    }

    private void runOnDeviceReview(Uri uri) {
        runReview(new VideoSource() {
            @Override
            public void configure(MediaMetadataRetriever retriever) throws Exception {
                retriever.setDataSource(MainActivity.this, uri);
            }

            @Override
            public AssetFileDescriptor open() throws Exception {
                return getContentResolver().openAssetFileDescriptor(uri, "r");
            }

            @Override
            public String label() {
                return uri.toString();
            }

            @Override
            public Uri previewUri() {
                return uri;
            }

            @Override
            public String previewPath() {
                return null;
            }
        });
    }

    private void runFile(String path) {
        runReview(new VideoSource() {
            @Override
            public void configure(MediaMetadataRetriever retriever) {
                retriever.setDataSource(path);
            }

            @Override
            public AssetFileDescriptor open() throws Exception {
                return new AssetFileDescriptor(android.os.ParcelFileDescriptor.open(
                        new java.io.File(path),
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY
                ), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
            }

            @Override
            public String label() {
                return path;
            }

            @Override
            public Uri previewUri() {
                return Uri.fromFile(new java.io.File(path));
            }

            @Override
            public String previewPath() {
                return path;
            }
        });
    }

    private void runReview(VideoSource source) {
        pickButton.setEnabled(false);
        if (demoButton != null) {
            demoButton.setEnabled(false);
        }
        progress.setProgress(0);
        resultCard.setVisibility(View.GONE);
        showScreen(processingScreen);
        progressTitle.setText("初始化姿态模型");
        progressBody.setText("视频来源:\n" + source.label());
        output.setText("初始化 MediaPipe Pose Landmarker...\n" + source.label());

        new Thread(() -> {
            long started = SystemClock.elapsedRealtime();
            List<FramePose> frames = new ArrayList<>();
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            PoseLandmarker landmarker = null;
            try {
                boolean currentLongMode = longVideoMode;
                source.configure(retriever);
                int durationMs = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
                int width = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
                int height = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
                int rotationDegrees = normalizeRotation(parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0));
                int displayWidth = rotationDegrees == 90 || rotationDegrees == 270 ? height : width;
                int displayHeight = rotationDegrees == 90 || rotationDegrees == 270 ? width : height;
                int targetFps = currentLongMode ? LONG_TARGET_FPS : SHORT_TARGET_FPS;
                int maxDurationMs = currentLongMode ? LONG_MAX_DURATION_MS : MAX_DURATION_MS;
                int modelInputMaxWidth = currentLongMode ? LONG_MODEL_INPUT_MAX_WIDTH : MODEL_INPUT_MAX_WIDTH;
                String cacheKey = cacheKey(source.label(), durationMs, width, height, rotationDegrees, currentLongMode);
                CachedReview cached = loadCachedReview(cacheKey);
                if (cached != null && !TextUtils.isEmpty(cached.videoPath) && new java.io.File(cached.videoPath).exists()) {
                    rememberHistoryKey(cached.key);
                    runOnUiThread(() -> displayCachedReview(cached, "已加载本地复盘缓存。"));
                    return;
                }
                int processDurationMs = Math.min(Math.max(durationMs, 0), maxDurationMs);
                int stepMs = Math.max(1, 1000 / targetFps);
                int expectedFrames = Math.max(1, processDurationMs / stepMs);
                int scaledWidth = displayWidth > 0 ? Math.min(displayWidth, modelInputMaxWidth) : modelInputMaxWidth;
                int scaledHeight = displayWidth > 0 && displayHeight > 0
                        ? Math.max(1, Math.round(displayHeight * (scaledWidth / (float) displayWidth)))
                        : modelInputMaxWidth;

                BaseOptions baseOptions = BaseOptions.builder()
                        .setModelAssetPath("pose_landmarker_lite.task")
                        .build();
                PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumPoses(1)
                        .setMinPoseDetectionConfidence(0.45f)
                        .setMinPosePresenceConfidence(0.45f)
                        .setMinTrackingConfidence(0.45f)
                        .build();
                landmarker = PoseLandmarker.createFromOptions(this, options);

                DecodeStats stats = decodeWithCodec(
                        source,
                        landmarker,
                        processDurationMs,
                        stepMs,
                        expectedFrames,
                        targetFps,
                        currentLongMode,
                        width,
                        height,
                        rotationDegrees,
                        durationMs,
                        scaledWidth,
                        scaledHeight,
                        frames
                );
                long decodeMs = stats.decodeMs;
                long poseMs = stats.poseMs;

                long scoreStart = SystemClock.elapsedRealtime();
                ReviewSummary summary = currentLongMode ? new ReviewSummary(new ArrayList<>()) : PoseScorer.score(frames, targetFps);
                long scoreMs = currentLongMode ? 0L : SystemClock.elapsedRealtime() - scoreStart;
                long elapsedMs = SystemClock.elapsedRealtime() - started;
                String report = currentLongMode
                        ? buildLongPoseReport(source.label(), durationMs, processDurationMs, width, height, scaledWidth, scaledHeight, targetFps, frames, elapsedMs, decodeMs, poseMs)
                        : buildReport(source.label(), durationMs, width, height, scaledWidth, scaledHeight, frames, elapsedMs, decodeMs, poseMs, scoreMs, summary);
                String scoreText = currentLongMode ? "长视频骨架模式：不进行动作评分" : buildScoreSummary(summary);
                String performanceText = buildPerformanceSummary(durationMs, width, height, scaledWidth, scaledHeight, frames, elapsedMs, decodeMs, poseMs, scoreMs);
                String eventText = currentLongMode ? "已完成全程骨架渲染。此模式不统计重发力窗口，可直接播放或导出骨架合成视频。" : buildEventSummary(summary);
                String cachedVideoPath = copySourceToCache(source, cacheKey);
                CachedReview cachedReview = new CachedReview(
                        cacheKey,
                        source.label(),
                        cachedVideoPath,
                        durationMs,
                        width,
                        height,
                        scaledWidth,
                        scaledHeight,
                        currentLongMode,
                        targetFps,
                        new ArrayList<>(frames),
                        summary,
                        report,
                        scoreText,
                        performanceText,
                        eventText
                );
                saveCachedReview(cachedReview);
                Log.i(TAG, report);
                runOnUiThread(() -> {
                    progress.setProgress(100);
                    progressTitle.setText(currentLongMode ? "骨架渲染完成" : "分析完成");
                    progressBody.setText(currentLongMode ? "已完成长视频端上抽帧和姿态识别。" : "已完成端上抽帧、姿态识别和评分。");
                    displayCachedReview(cachedReview, null);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    progressTitle.setText("分析失败");
                    progressBody.setText(stackTraceText(t));
                });
                runOnUiThread(() -> output.setText("端上计算失败:\n" + stackTraceText(t)));
            } finally {
                if (landmarker != null) {
                    landmarker.close();
                }
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> pickButton.setEnabled(true));
                if (demoButton != null) {
                    runOnUiThread(() -> demoButton.setEnabled(true));
                }
            }
        }, "on-device-review").start();
    }

    private void displayCachedReview(CachedReview review, String status) {
        currentReview = review;
        progress.setProgress(100);
        resultCard.setVisibility(View.VISIBLE);
        if (scoreSummary != null) {
            scoreSummary.setText(review.scoreText);
        }
        if (performanceSummary != null) {
            performanceSummary.setText(review.performanceText);
        }
        eventSummary.setText(status == null ? review.eventText : status + "\n" + review.eventText);
        eventCountText.setText(review.longMode ? "长视频骨架" : review.summary.events.size() + " 个重发力窗口");
        bindReviewVideoPath(review.videoPath, review.summary, review.frames);
        if (review.longMode) {
            renderLongPoseTimeline(review);
            showLongPoseStatus(review);
        } else {
            renderTimeline(review.summary);
            showStrokeStatus(review.summary.events.isEmpty() ? null : review.summary.events.get(0));
        }
        output.setText(review.report);
        if (exportButton != null) {
            exportButton.setText(review.longMode ? "下载骨架标注视频" : "下载姿态合成视频");
            exportButton.setEnabled(!TextUtils.isEmpty(review.videoPath) && !review.frames.isEmpty());
        }
        updateSlowMotionControls();
        showScreen(dashboardScreen);
    }

    private void bindReviewVideo(VideoSource source, ReviewSummary summary, List<FramePose> frames) {
        MediaController controller = new MediaController(this);
        controller.setAnchorView(reviewVideo);
        reviewVideo.setMediaController(controller);
        if (poseOverlay != null) {
            poseOverlay.bind(reviewVideo, frames);
        }
        String path = ensurePlayablePreviewPath(source);
        bindReviewVideoPath(path, summary, frames);
    }

    private void bindReviewVideoPath(String path, ReviewSummary summary, List<FramePose> frames) {
        MediaController controller = new MediaController(this);
        controller.setAnchorView(reviewVideo);
        reviewVideo.setMediaController(controller);
        if (poseOverlay != null) {
            poseOverlay.bind(reviewVideo, frames);
        }
        if (!TextUtils.isEmpty(path)) {
            reviewVideo.setVideoPath(path);
        } else {
            return;
        }
        int previewStartMs = summary.events.isEmpty()
                ? 1
                : (int) Math.max(1, summary.events.get(0).timeSec * 1000);
        reviewVideo.setOnPreparedListener(player -> {
            reviewMediaPlayer = player;
            player.setVolume(0f, 0f);
            appliedPlaybackSpeed = 1.0f;
            setReviewPlaybackSpeed(1.0f);
            reviewVideo.seekTo(previewStartMs);
            if (poseOverlay != null) {
                poseOverlay.refreshNow();
            }
            startPlaybackSpeedMonitor();
            reviewVideo.start();
            initialPreviewPauseRunnable = () -> {
                if (reviewVideo != null && reviewVideo.isPlaying()) {
                    reviewVideo.pause();
                }
                if (poseOverlay != null) {
                    poseOverlay.refreshNow();
                }
                initialPreviewPauseRunnable = null;
            };
            reviewVideo.postDelayed(initialPreviewPauseRunnable, 900);
        });
        reviewVideo.setOnCompletionListener(player -> {
            setReviewPlaybackSpeed(1.0f);
            if (poseOverlay != null) {
                poseOverlay.refreshNow();
            }
        });
    }

    private void playStrokeSlowMotion(StrokeScore stroke) {
        if (stroke == null || reviewVideo == null) {
            return;
        }
        cancelInitialPreviewPause();
        int contactMs = (int) Math.max(0, stroke.timeSec * 1000);
        int startMs = Math.max(0, contactMs - 450);
        reviewVideo.seekTo(startMs);
        startPlaybackSpeedMonitor();
        updateAutoPlaybackSpeed();
        if (poseOverlay != null) {
            poseOverlay.refreshNow();
            poseOverlay.postDelayed(poseOverlay::refreshNow, 120);
        }
        reviewVideo.start();
    }

    private void setReviewPlaybackSpeed(float speed) {
        if (reviewVideo == null) {
            return;
        }
        float normalized = Math.max(0.2f, Math.min(1.0f, speed));
        if (Math.abs(appliedPlaybackSpeed - normalized) < 0.01f) {
            return;
        }
        try {
            android.media.MediaPlayer player = reviewMediaPlayer;
            if (player != null) {
                android.media.PlaybackParams params = player.getPlaybackParams();
                params.setSpeed(normalized);
                player.setPlaybackParams(params);
                appliedPlaybackSpeed = normalized;
            }
        } catch (Throwable speedError) {
            Log.w(TAG, "set playback speed failed", speedError);
        }
    }

    private void startPlaybackSpeedMonitor() {
        if (reviewVideo == null) {
            return;
        }
        if (playbackSpeedMonitor == null) {
            playbackSpeedMonitor = new Runnable() {
                @Override
                public void run() {
                    updateAutoPlaybackSpeed();
                    if (reviewVideo != null) {
                        reviewVideo.postDelayed(this, 90L);
                    }
                }
            };
        }
        reviewVideo.removeCallbacks(playbackSpeedMonitor);
        reviewVideo.post(playbackSpeedMonitor);
    }

    private void stopPlaybackAutomation() {
        cancelInitialPreviewPause();
        if (reviewVideo != null && playbackSpeedMonitor != null) {
            reviewVideo.removeCallbacks(playbackSpeedMonitor);
        }
        setReviewPlaybackSpeed(1.0f);
    }

    private void cancelInitialPreviewPause() {
        if (reviewVideo != null && initialPreviewPauseRunnable != null) {
            reviewVideo.removeCallbacks(initialPreviewPauseRunnable);
        }
        initialPreviewPauseRunnable = null;
    }

    private void updateAutoPlaybackSpeed() {
        if (reviewVideo == null) {
            return;
        }
        StrokeScore active = slowMotionEnabled && reviewVideo.isPlaying()
                ? slowMotionStrokeAt(reviewVideo.getCurrentPosition())
                : null;
        setReviewPlaybackSpeed(active == null ? 1.0f : slowMotionSpeed);
        if (active != null && (selectedStroke == null || selectedStroke.index != active.index)) {
            showStrokeStatus(active);
        }
    }

    private StrokeScore slowMotionStrokeAt(int currentMs) {
        CachedReview review = currentReview;
        if (review == null || review.summary == null || review.summary.events == null) {
            return null;
        }
        for (StrokeScore stroke : review.summary.events) {
            int contactMs = (int) Math.round(stroke.timeSec * 1000.0);
            int startMs = Math.max(0, contactMs - 420);
            int endMs = contactMs + 720;
            if (currentMs >= startMs && currentMs <= endMs) {
                return stroke;
            }
        }
        return null;
    }

    private void cycleSlowMotionSpeed() {
        if (slowMotionSpeed >= 0.74f) {
            slowMotionSpeed = 0.5f;
        } else if (slowMotionSpeed >= 0.49f) {
            slowMotionSpeed = 0.33f;
        } else {
            slowMotionSpeed = 0.75f;
        }
    }

    private void updateSlowMotionControls() {
        boolean available = currentReview == null || !currentReview.longMode;
        if (slowMotionToggleButton != null) {
            slowMotionToggleButton.setEnabled(available);
            slowMotionToggleButton.setText(available ? (slowMotionEnabled ? "慢动作：开" : "慢动作：关") : "慢动作：短视频可用");
        }
        if (slowMotionSpeedButton != null) {
            slowMotionSpeedButton.setEnabled(available && slowMotionEnabled);
            slowMotionSpeedButton.setText(available ? String.format(Locale.US, "速度：%.2gx", slowMotionSpeed) : "速度：--");
        }
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private String ensurePlayablePreviewPath(VideoSource source) {
        java.io.File dir = getExternalCacheDir();
        if (dir == null) {
            dir = getCacheDir();
        }
        java.io.File out = new java.io.File(dir, "review_preview.mp4");
        try (AssetFileDescriptor afd = source.open();
             java.io.FileInputStream input = new java.io.FileInputStream(afd.getFileDescriptor());
             java.io.FileOutputStream outputStream = new java.io.FileOutputStream(out)) {
            long offset = Math.max(0, afd.getStartOffset());
            while (offset > 0) {
                long skipped = input.skip(offset);
                if (skipped <= 0) {
                    break;
                }
                offset -= skipped;
            }
            long remaining = afd.getLength();
            byte[] buffer = new byte[1024 * 256];
            while (remaining != 0) {
                int max = remaining > 0 ? (int) Math.min(buffer.length, remaining) : buffer.length;
                int read = input.read(buffer, 0, max);
                if (read < 0) {
                    break;
                }
                outputStream.write(buffer, 0, read);
                if (remaining > 0) {
                    remaining -= read;
                }
            }
            out.setReadable(true, false);
            return out.getAbsolutePath();
        } catch (Exception copyError) {
            Log.w(TAG, "preview copy failed", copyError);
            return source.previewPath();
        }
    }

    private void loadLastReviewIfAvailable() {
        refreshHistoryPanel();
    }

    private String cacheKey(String label, int durationMs, int width, int height, int rotationDegrees, boolean longMode) {
        String raw = CACHE_SCHEMA_VERSION + "|" + (longMode ? "long" : "short") + "|" + label + "|" + durationMs + "|" + width + "|" + height + "|" + rotationDegrees;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(16, bytes.length); i++) {
                builder.append(String.format(Locale.US, "%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static int normalizeRotation(int value) {
        int rotation = value % 360;
        if (rotation < 0) {
            rotation += 360;
        }
        if (rotation == 90 || rotation == 180 || rotation == 270) {
            return rotation;
        }
        return 0;
    }

    private java.io.File reviewCacheDir(String key) {
        java.io.File root = new java.io.File(getFilesDir(), "review_cache");
        java.io.File dir = new java.io.File(root, key);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private String copySourceToCache(VideoSource source, String key) {
        java.io.File out = new java.io.File(reviewCacheDir(key), "source.mp4");
        try (AssetFileDescriptor afd = source.open();
             java.io.FileInputStream input = new java.io.FileInputStream(afd.getFileDescriptor());
             java.io.FileOutputStream outputStream = new java.io.FileOutputStream(out)) {
            long offset = Math.max(0, afd.getStartOffset());
            while (offset > 0) {
                long skipped = input.skip(offset);
                if (skipped <= 0) break;
                offset -= skipped;
            }
            long remaining = afd.getLength();
            byte[] buffer = new byte[1024 * 256];
            while (remaining != 0) {
                int max = remaining > 0 ? (int) Math.min(buffer.length, remaining) : buffer.length;
                int read = input.read(buffer, 0, max);
                if (read < 0) break;
                outputStream.write(buffer, 0, read);
                if (remaining > 0) remaining -= read;
            }
            out.setReadable(true, false);
            return out.getAbsolutePath();
        } catch (Exception copyError) {
            Log.w(TAG, "cache source copy failed", copyError);
            return source.previewPath();
        }
    }

    private void saveCachedReview(CachedReview review) {
        if (review == null || TextUtils.isEmpty(review.key)) {
            return;
        }
        java.io.File out = new java.io.File(reviewCacheDir(review.key), "review.bin");
        try (java.io.ObjectOutputStream stream = new java.io.ObjectOutputStream(new java.io.FileOutputStream(out))) {
            stream.writeObject(review);
            getPreferences(MODE_PRIVATE).edit()
                    .putString("last_review_key", review.key)
                    .putString("last_review_schema", CACHE_SCHEMA_VERSION)
                    .apply();
            rememberHistoryKey(review.key);
        } catch (Exception saveError) {
            Log.w(TAG, "review cache save failed", saveError);
        }
    }

    private CachedReview loadCachedReview(String key) {
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        java.io.File file = new java.io.File(reviewCacheDir(key), "review.bin");
        if (!file.exists()) {
            return null;
        }
        try (java.io.ObjectInputStream stream = new java.io.ObjectInputStream(new java.io.FileInputStream(file))) {
            Object value = stream.readObject();
            return value instanceof CachedReview ? (CachedReview) value : null;
        } catch (Exception loadError) {
            Log.w(TAG, "review cache load failed", loadError);
            return null;
        }
    }

    private List<String> loadHistoryKeys() {
        String schema = getPreferences(MODE_PRIVATE).getString("history_schema", null);
        if (!CACHE_SCHEMA_VERSION.equals(schema)) {
            return new ArrayList<>();
        }
        String raw = getPreferences(MODE_PRIVATE).getString("review_history_keys", "");
        ArrayList<String> keys = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return keys;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            if (!TextUtils.isEmpty(part) && !keys.contains(part)) {
                keys.add(part);
            }
        }
        return keys;
    }

    private void saveHistoryKeys(List<String> keys) {
        getPreferences(MODE_PRIVATE).edit()
                .putString("history_schema", CACHE_SCHEMA_VERSION)
                .putString("review_history_keys", TextUtils.join(",", keys))
                .apply();
    }

    private void rememberHistoryKey(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        List<String> keys = loadHistoryKeys();
        keys.remove(key);
        keys.add(0, key);
        while (keys.size() > MAX_HISTORY_REVIEWS) {
            String removed = keys.remove(keys.size() - 1);
            deleteReviewCache(removed);
        }
        saveHistoryKeys(keys);
        runOnUiThread(this::refreshHistoryPanel);
    }

    private void refreshHistoryPanel() {
        if (historyList == null || historyHint == null) {
            return;
        }
        List<String> keys = loadHistoryKeys();
        ArrayList<String> validKeys = new ArrayList<>();
        ArrayList<CachedReview> reviews = new ArrayList<>();
        for (String key : keys) {
            CachedReview review = loadCachedReview(key);
            if (review == null || TextUtils.isEmpty(review.videoPath) || !new java.io.File(review.videoPath).exists()) {
                deleteReviewCache(key);
                continue;
            }
            validKeys.add(key);
            reviews.add(review);
        }
        if (validKeys.size() != keys.size()) {
            saveHistoryKeys(validKeys);
        }
        historyList.removeAllViews();
        historyHint.setText(reviews.isEmpty()
                ? "暂无历史复盘。完成一次上传后会自动保留在本机。"
                : "已保留 " + reviews.size() + "/" + MAX_HISTORY_REVIEWS + " 条本机复盘，点击任意记录继续查看。");
        if (reviews.isEmpty()) {
            TextView empty = text("暂无历史记录", 14, 0xffA9C7BB, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x22081410);
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(1), 0x22D7F8DF);
            empty.setBackground(bg);
            historyList.addView(empty, topFixedMargin(0, dp(64)));
            return;
        }
        for (CachedReview review : reviews) {
            Button item = historyButton(review);
            item.setOnClickListener(v -> displayCachedReview(review, "已从历史记录加载。"));
            historyList.addView(item, topFixedMargin(dp(8), dp(72)));
        }
    }

    private Button historyButton(CachedReview review) {
        String name = shortLabel(review.sourceLabel);
        String modeDetail = review.longMode
                ? "长视频骨架 · " + review.frames.size() + " 帧"
                : review.summary.events.size() + " 个重发力窗口";
        String detail = format(review.durationMs / 1000.0) + "s"
                + " · " + modeDetail
                + " · " + cacheTimeText(review.key);
        return timelineButton(name + "\n" + detail);
    }

    private String shortLabel(String value) {
        if (TextUtils.isEmpty(value)) {
            return "未命名视频";
        }
        String text = value;
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        if (slash >= 0 && slash < text.length() - 1) {
            text = text.substring(slash + 1);
        }
        if (text.length() > 32) {
            text = text.substring(0, 12) + "..." + text.substring(text.length() - 14);
        }
        return text;
    }

    private void renderLongPoseTimeline(CachedReview review) {
        timelineStrip.removeAllViews();
        TextView item = text("长视频模式只生成全程骨架标注，不做重发力评分。", 14, 0xffA9C7BB, Typeface.NORMAL);
        item.setLineSpacing(dp(2), 1.0f);
        timelineStrip.addView(item);
    }

    private void showLongPoseStatus(CachedReview review) {
        selectedStroke = null;
        expandedEvidenceType = "";
        strokeStatusTitle.setText("长视频骨架标注");
        strokeStatusTime.setText("已抽样 " + review.frames.size() + " 帧 · " + Math.max(1, review.sampleFps) + "fps");
        timingValue.setText("击球时机\n未评分");
        chainValue.setText("发力链\n未评分");
        recoveryValue.setText("回位恢复\n未评分");
        actionLevelValue.setText("动作质量参考：长视频模式不计算评分");
        evidenceDetail.setVisibility(View.GONE);
    }

    private String cacheTimeText(String key) {
        java.io.File file = new java.io.File(reviewCacheDir(key), "review.bin");
        long time = file.exists() ? file.lastModified() : 0L;
        if (time <= 0L) {
            return "已完成";
        }
        java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        return formatter.format(new java.util.Date(time));
    }

    private void deleteReviewCache(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        deleteRecursively(reviewCacheDir(key));
    }

    private void deleteRecursively(java.io.File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void renderTimeline(ReviewSummary summary) {
        timelineStrip.removeAllViews();
        if (summary.events.isEmpty()) {
            TextView empty = text("未检测到明显重发力窗口", 14, 0xffA9C7BB, Typeface.NORMAL);
            timelineStrip.addView(empty);
            eventSummary.setText("当前视频可复盘，但没有稳定识别到适合打分的重发力窗口。");
            return;
        }
        for (StrokeScore stroke : summary.events) {
            Button marker = timelineButton("#" + (stroke.index + 1) + "\n" + format(stroke.timeSec) + "s");
            marker.setOnClickListener(v -> {
                showStrokeStatus(stroke);
                playStrokeSlowMotion(stroke);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(86), dp(62));
            params.setMargins(0, 0, dp(8), 0);
            timelineStrip.addView(marker, params);
        }
        eventSummary.setText("点击时间轴可跳转到对应重发力窗口；播放经过重发力窗口时会按当前设置自动慢放。");
    }

    private void exportCurrentReview() {
        CachedReview review = currentReview;
        if (review == null || TextUtils.isEmpty(review.videoPath) || review.frames.isEmpty()) {
            eventSummary.setText("暂无可导出的复盘结果。请先完成一次视频复盘。");
            return;
        }
        exportButton.setEnabled(false);
        eventSummary.setText("正在合成带骨架的视频，请稍等。");
        new Thread(() -> {
            try {
                java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (dir == null) {
                    dir = getCacheDir();
                }
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                java.io.File out = new java.io.File(dir, "shuttle_pose_" + System.currentTimeMillis() + ".mp4");
                renderPoseVideo(review, out);
                Uri saved = publishVideo(out);
                runOnUiThread(() -> {
                    exportButton.setEnabled(true);
                    eventSummary.setText("姿态合成视频已保存：\n" + (saved != null ? saved.toString() : out.getAbsolutePath()));
                });
            } catch (Throwable exportError) {
                Log.e(TAG, "pose video export failed", exportError);
                runOnUiThread(() -> {
                    exportButton.setEnabled(true);
                    eventSummary.setText("导出失败：\n" + exportError.getMessage());
                });
            }
        }, "pose-video-export").start();
    }

    private void renderPoseVideo(CachedReview review, java.io.File out) throws Exception {
        int outWidth = 640;
        int outHeight = review.width > 0 && review.height > 0
                ? Math.max(2, Math.round(review.height * (outWidth / (float) review.width)))
                : 360;
        if ((outHeight & 1) == 1) {
            outHeight += 1;
        }
        int outputFps = Math.max(1, review.sampleFps > 0 ? review.sampleFps : TARGET_FPS);
        int colorFormat = chooseAvcColorFormat();
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", outWidth, outHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_400_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, outputFps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
        MediaMuxer muxer = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        boolean muxerStarted = false;
        int trackIndex = -1;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            retriever.setDataSource(review.videoPath);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int frameIndex = 0;
            for (FramePose frame : review.frames) {
                Bitmap source = retriever.getFrameAtTime(
                        (long) Math.max(0, frame.timeSec * 1_000_000L),
                        MediaMetadataRetriever.OPTION_CLOSEST
                );
                if (source == null) {
                    continue;
                }
                Bitmap composed = composePoseFrame(source, frame, outWidth, outHeight);
                byte[] yuv = bitmapToYuv420(composed, colorFormat);
                composed.recycle();
                source.recycle();

                int inputIndex = encoder.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    java.nio.ByteBuffer input = encoder.getInputBuffer(inputIndex);
                    if (input != null) {
                        input.clear();
                        input.put(yuv);
                    }
                    long ptsUs = frameIndex * 1_000_000L / outputFps;
                    encoder.queueInputBuffer(inputIndex, 0, yuv.length, ptsUs, 0);
                    frameIndex++;
                }
                DrainResult drained = drainEncoder(encoder, muxer, info, muxerStarted, trackIndex, false);
                muxerStarted = drained.muxerStarted;
                trackIndex = drained.trackIndex;
            }
            int inputIndex = encoder.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                long ptsUs = Math.max(1, frameIndex) * 1_000_000L / outputFps;
                encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drainEncoder(encoder, muxer, info, muxerStarted, trackIndex, true);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
            try {
                encoder.stop();
            } catch (Exception ignored) {
            }
            encoder.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                muxer.release();
            }
        }
    }

    private DrainResult drainEncoder(
            MediaCodec encoder,
            MediaMuxer muxer,
            MediaCodec.BufferInfo info,
            boolean muxerStarted,
            int trackIndex,
            boolean end
    ) {
        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(info, end ? 10_000 : 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) break;
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }
            java.nio.ByteBuffer encoded = encoder.getOutputBuffer(outputIndex);
            if (encoded != null && info.size > 0 && muxerStarted) {
                encoded.position(info.offset);
                encoded.limit(info.offset + info.size);
                muxer.writeSampleData(trackIndex, encoded, info);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            encoder.releaseOutputBuffer(outputIndex, false);
            if (eos) {
                break;
            }
            if (!end) {
                break;
            }
        }
        return new DrainResult(muxerStarted, trackIndex);
    }

    private Bitmap composePoseFrame(Bitmap source, FramePose frame, int outWidth, int outHeight) {
        Bitmap out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xff020806);
        RectF fit = fitRect(source.getWidth(), source.getHeight(), outWidth, outHeight);
        canvas.drawBitmap(source, null, fit, null);
        drawPose(canvas, frame, fit);
        return out;
    }

    private void drawPose(Canvas canvas, FramePose frame, RectF fit) {
        if (frame == null || !frame.hasPose()) {
            return;
        }
        Paint bone = new Paint(Paint.ANTI_ALIAS_FLAG);
        bone.setColor(0xff9BF7C5);
        bone.setStrokeWidth(5f);
        bone.setStrokeCap(Paint.Cap.ROUND);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(0x5520C878);
        glow.setStrokeWidth(10f);
        glow.setStrokeCap(Paint.Cap.ROUND);
        Paint joint = new Paint(Paint.ANTI_ALIAS_FLAG);
        joint.setColor(0xffF7D76E);
        joint.setStyle(Paint.Style.FILL);
        for (int[] link : PoseOverlayView.BONES) {
            Point2 a = framePoint(frame, link[0]);
            Point2 b = framePoint(frame, link[1]);
            if (a == null || b == null) continue;
            float ax = fit.left + (float) (a.x / Math.max(1, frame.width)) * fit.width();
            float ay = fit.top + (float) (a.y / Math.max(1, frame.height)) * fit.height();
            float bx = fit.left + (float) (b.x / Math.max(1, frame.width)) * fit.width();
            float by = fit.top + (float) (b.y / Math.max(1, frame.height)) * fit.height();
            canvas.drawLine(ax, ay, bx, by, glow);
            canvas.drawLine(ax, ay, bx, by, bone);
        }
        for (Point2 point : frame.pose) {
            if (point == null || point.visibility < 0.35) continue;
            float x = fit.left + (float) (point.x / Math.max(1, frame.width)) * fit.width();
            float y = fit.top + (float) (point.y / Math.max(1, frame.height)) * fit.height();
            canvas.drawCircle(x, y, 4.5f, joint);
        }
    }

    private Point2 framePoint(FramePose frame, int index) {
        if (index < 0 || index >= frame.pose.length) {
            return null;
        }
        Point2 point = frame.pose[index];
        return point != null && point.visibility >= 0.35 ? point : null;
    }

    private RectF fitRect(int srcW, int srcH, int dstW, int dstH) {
        float scale = Math.min(dstW / (float) Math.max(1, srcW), dstH / (float) Math.max(1, srcH));
        float drawW = srcW * scale;
        float drawH = srcH * scale;
        return new RectF((dstW - drawW) / 2f, (dstH - drawH) / 2f, (dstW + drawW) / 2f, (dstH + drawH) / 2f);
    }

    private int chooseAvcColorFormat() throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
        try {
            MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType("video/avc");
            int fallback = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
            for (int color : caps.colorFormats) {
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return color;
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) fallback = color;
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) fallback = color;
            }
            return fallback;
        } finally {
            codec.release();
        }
    }

    private byte[] bitmapToYuv420(Bitmap bitmap, int colorFormat) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        byte[] yuv = new byte[width * height * 3 / 2];
        boolean planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
        int yIndex = 0;
        int uIndex = width * height;
        int vIndex = planar ? uIndex + (width * height / 4) : uIndex + 1;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int c = argb[j * width + i];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                yuv[yIndex++] = (byte) clampByte(y);
                if ((j & 1) == 0 && (i & 1) == 0) {
                    if (planar) {
                        yuv[uIndex++] = (byte) clampByte(u);
                        yuv[vIndex++] = (byte) clampByte(v);
                    } else {
                        yuv[uIndex] = (byte) clampByte(u);
                        yuv[vIndex] = (byte) clampByte(v);
                        uIndex += 2;
                        vIndex += 2;
                    }
                }
            }
        }
        return yuv;
    }

    private int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private Uri publishVideo(java.io.File file) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShuttlePoseReview");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("cannot create media store item");
            }
            try (java.io.InputStream in = new java.io.FileInputStream(file);
                 java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IllegalStateException("cannot open media output stream");
                }
                byte[] buffer = new byte[1024 * 256];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        }
        MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        return Uri.fromFile(file);
    }

    private Button timelineButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(0xffF2FFF7);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff081410);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x55D7F8DF);
        button.setBackground(bg);
        return button;
    }

    private void showStrokeStatus(StrokeScore stroke) {
        selectedStroke = stroke;
        expandedEvidenceType = "";
        if (stroke == null) {
            strokeStatusTitle.setText("没有检测到重发力窗口");
            strokeStatusTime.setText("建议使用近端球员清晰、身体完整入镜的视频。");
            timingValue.setText("击球时机\n--");
            chainValue.setText("发力链\n--");
            recoveryValue.setText("回位恢复\n--");
            actionLevelValue.setText("综合质量参考：--");
            evidenceDetail.setVisibility(View.GONE);
            return;
        }
        strokeStatusTitle.setText("第 " + (stroke.index + 1) + " 次重发力 · " + stroke.side);
        strokeStatusTime.setText("时间点 " + format(stroke.timeSec) + "s");
        timingValue.setText("击球时机\n" + format(stroke.timing));
        chainValue.setText("发力链\n" + format(stroke.chain));
        recoveryValue.setText("回位恢复\n" + format(stroke.recovery));
        actionLevelValue.setText("综合质量参考：" + stroke.actionLevel + " · 综合 " + format(stroke.overall));
        evidenceDetail.setVisibility(View.GONE);
    }

    private void toggleEvidence(String type) {
        if (selectedStroke == null) {
            return;
        }
        if (type.equals(expandedEvidenceType) && evidenceDetail.getVisibility() == View.VISIBLE) {
            evidenceDetail.setVisibility(View.GONE);
            expandedEvidenceType = "";
            return;
        }
        expandedEvidenceType = type;
        evidenceDetail.setText(buildEvidenceText(selectedStroke, type));
        evidenceDetail.setVisibility(View.VISIBLE);
    }

    private String buildEvidenceText(StrokeScore stroke, String type) {
        if ("timing".equals(type)) {
            return formatEvidenceReport(buildTimingReport(stroke));
        }
        if ("chain".equals(type)) {
            return formatEvidenceReport(buildChainReport(stroke));
        }
        return formatEvidenceReport(buildRecoveryReport(stroke));
    }

    private EvidenceReport buildTimingReport(StrokeScore stroke) {
        EvidenceReport report = new EvidenceReport(
                "击球时机证据",
                stroke.timing,
                "这一项看的是击球点是否主动、手臂是否展开、击球前是否已经完成准备。",
                timingSuggestion(stroke)
        );
        report.items.add(new EvidenceItem(
                "击球点高度",
                stroke.heightScore,
                heightInsight(stroke.heightScore),
                heightAdvice(stroke.heightScore)
        ));
        report.items.add(new EvidenceItem(
                "手臂伸展",
                stroke.elbowScore,
                elbowInsight(stroke.elbowScore),
                elbowAdvice(stroke.elbowScore)
        ));
        report.items.add(new EvidenceItem(
                "提前准备",
                stroke.prepScore,
                prepInsight(stroke.prepScore),
                prepAdvice(stroke.prepScore)
        ));
        report.confidenceScore = stroke.confidenceScore;
        return report;
    }

    private EvidenceReport buildChainReport(StrokeScore stroke) {
        EvidenceReport report = new EvidenceReport(
                "发力链证据",
                stroke.chain,
                "这一项看的是身体、手臂、手腕是否形成连续传导，而不是只靠手臂硬打。",
                chainSuggestion(stroke)
        );
        report.items.add(new EvidenceItem(
                "整体爆发",
                stroke.energyScore,
                energyInsight(stroke.energyScore),
                energyAdvice(stroke.energyScore)
        ));
        report.items.add(new EvidenceItem(
                "发力顺序",
                stroke.orderScore,
                orderInsight(stroke.orderScore),
                orderAdvice(stroke.orderScore)
        ));
        report.items.add(new EvidenceItem(
                "手腕释放",
                stroke.wristLateScore,
                wristInsight(stroke.wristLateScore),
                wristAdvice(stroke.wristLateScore)
        ));
        report.items.add(new EvidenceItem(
                "下肢参与",
                stroke.kneeLoadScore,
                kneeInsight(stroke.kneeLoadScore),
                kneeAdvice(stroke.kneeLoadScore)
        ));
        report.confidenceScore = stroke.confidenceScore;
        return report;
    }

    private EvidenceReport buildRecoveryReport(StrokeScore stroke) {
        EvidenceReport report = new EvidenceReport(
                "回位恢复证据",
                stroke.recovery,
                "这一项看的是击球后是否能快速收住动作，并回到可以衔接下一拍的状态。",
                recoverySuggestion(stroke)
        );
        report.items.add(new EvidenceItem(
                "回位速度",
                stroke.recoveryTimeScore,
                recoveryTimeInsight(stroke),
                recoveryTimeAdvice(stroke)
        ));
        report.items.add(new EvidenceItem(
                "动作收束",
                stroke.residualScore,
                residualInsight(stroke.residualScore),
                residualAdvice(stroke.residualScore)
        ));
        report.items.add(new EvidenceItem(
                "身体回正",
                stroke.postureScore,
                postureInsight(stroke.postureScore),
                postureAdvice(stroke.postureScore)
        ));
        report.confidenceScore = stroke.confidenceScore;
        return report;
    }

    private String formatEvidenceReport(EvidenceReport report) {
        StringBuilder out = new StringBuilder();
        out.append(report.title)
                .append("\n本项判断: ")
                .append(gradeLabel(report.score))
                .append(" · ")
                .append(overallVerdict(report.score))
                .append("\n说明: ")
                .append(report.summary)
                .append("\n画面可信度: ")
                .append(confidenceLabel(report.confidenceScore))
                .append(" · ")
                .append(confidenceInsight(report.confidenceScore))
                .append("\n\n证据")
                .append(formatEvidenceItem(report.items.get(0)));
        for (int i = 1; i < report.items.size(); i++) {
            out.append(formatEvidenceItem(report.items.get(i)));
        }
        out.append("\n\n训练建议: ").append(report.suggestion);
        out.append("\n提示: 结果来自端上姿态规则，会受视角、遮挡和球员远近影响，适合做动作复盘参考。");
        return out.toString();
    }

    private String formatEvidenceItem(EvidenceItem item) {
        return "\n- " + item.name + " " + gradeCode(item.score)
                + ": " + item.insight
                + " 建议: " + item.advice;
    }

    private String gradeLabel(double score) {
        return gradeCode(score) + " 档";
    }

    private String gradeCode(double score) {
        if (score >= 88) return "S";
        if (score >= 78) return "A";
        if (score >= 65) return "B";
        if (score >= 50) return "C";
        return "D";
    }

    private String overallVerdict(double score) {
        if (score >= 88) return "表现突出";
        if (score >= 78) return "质量较好";
        if (score >= 65) return "基本成立";
        if (score >= 50) return "需要加强";
        return "优先修正";
    }

    private String confidenceLabel(double score) {
        if (score >= 75) return "高";
        if (score >= 55) return "中";
        return "低";
    }

    private String confidenceInsight(double score) {
        if (score >= 75) return "关键点比较稳定，本项判断可作为主要参考。";
        if (score >= 55) return "结果可以参考，但局部遮挡或侧面角度可能改变判断。";
        return "关键点不够稳定，本项更适合作为提示，不建议当作最终结论。";
    }

    private String heightInsight(double score) {
        if (score >= 88) return "击球点很主动，身体有机会在高点完成处理。";
        if (score >= 78) return "击球点较主动，不太像被球压住的状态。";
        if (score >= 65) return "击球点基本可用，但还有继续抢高点的空间。";
        if (score >= 50) return "击球点偏被动，容易让动作变成补救。";
        return "击球点明显偏被动，通常会影响发力和线路选择。";
    }

    private String heightAdvice(double score) {
        if (score >= 78) return "保持提前移动和举拍节奏。";
        if (score >= 65) return "多练启动后第一时间架拍。";
        return "先把判断、移动和举拍提前，不急着追求爆发。";
    }

    private String elbowInsight(double score) {
        if (score >= 88) return "击球瞬间手臂展开充分，发力空间很好。";
        if (score >= 78) return "手臂展开较好，能给球拍留出加速空间。";
        if (score >= 65) return "伸展基本够用，但还可以更舒展。";
        if (score >= 50) return "手臂展开不足，发力容易被压缩。";
        return "击球时手臂明显受限，容易只靠局部发力。";
    }

    private String elbowAdvice(double score) {
        if (score >= 78) return "继续保持击球前的空间感。";
        if (score >= 65) return "练习击球前让肘和拍面更早到位。";
        return "先练放松架拍和迎球，不要等球贴近身体再出手。";
    }

    private String prepInsight(double score) {
        if (score >= 88) return "击球前准备很充分，动作有预备节奏。";
        if (score >= 78) return "击球前已有明显准备，出手不仓促。";
        if (score >= 65) return "准备动作基本存在，但节奏还可以更早。";
        if (score >= 50) return "准备偏晚，击球前容易临时补动作。";
        return "准备不足，动作更像被球带着走。";
    }

    private String prepAdvice(double score) {
        if (score >= 78) return "保持分腿垫步后的快速架拍。";
        if (score >= 65) return "把准备动作提前到对方出球后的第一拍节奏。";
        return "先做无球的判断、侧身、举拍连贯练习。";
    }

    private String energyInsight(double score) {
        if (score >= 88) return "这一拍加速明显，动作有比较强的爆发感。";
        if (score >= 78) return "整体加速较好，能看到有效发力。";
        if (score >= 65) return "有发力动作，但爆发还不够集中。";
        if (score >= 50) return "动作能量偏弱，可能更像过渡处理。";
        return "加速不明显，发力动作没有完整释放出来。";
    }

    private String energyAdvice(double score) {
        if (score >= 78) return "继续保持放松到加速的节奏。";
        if (score >= 65) return "练习最后一小段集中加速。";
        return "先练完整挥拍轨迹，再逐步加速度。";
    }

    private String orderInsight(double score) {
        if (score >= 88) return "身体到手臂再到手腕的传导很顺。";
        if (score >= 78) return "发力顺序较清楚，身体参与不是孤立的。";
        if (score >= 65) return "传导基本成立，但部分环节还不够分明。";
        if (score >= 50) return "发力顺序偏混在一起，力量传递效率一般。";
        return "传导顺序不清楚，容易变成手臂单独用力。";
    }

    private String orderAdvice(double score) {
        if (score >= 78) return "继续保持先身体、后拍头的节奏。";
        if (score >= 65) return "用慢动作练蹬转、转体、挥拍的先后关系。";
        return "先降低速度，拆开下肢、躯干、手臂的发力顺序。";
    }

    private String wristInsight(double score) {
        if (score >= 88) return "手腕释放非常贴近击球点，出拍集中。";
        if (score >= 78) return "手腕释放时机较好，拍头加速比较集中。";
        if (score >= 65) return "释放时机基本可用，但还可以更贴近击球点。";
        if (score >= 50) return "释放时机偏散，击球前后加速不够集中。";
        return "手腕释放和击球点错开，力量容易漏掉。";
    }

    private String wristAdvice(double score) {
        if (score >= 78) return "保持放松握拍，最后一瞬间再加速。";
        if (score >= 65) return "练习击球前放松、触球瞬间集中发力。";
        return "先减少提前甩腕，找准触球瞬间的拍头加速。";
    }

    private String kneeInsight(double score) {
        if (score >= 88) return "下肢加载充分，身体参与感很强。";
        if (score >= 78) return "下肢参与较明显，发力有身体支撑。";
        if (score >= 65) return "有一定下肢参与，但主动性还可以更强。";
        if (score >= 50) return "下肢参与偏少，更多依赖上肢完成动作。";
        return "下肢加载不足，身体没有很好地给挥拍供力。";
    }

    private String kneeAdvice(double score) {
        if (score >= 78) return "继续保持击球前的蹬转节奏。";
        if (score >= 65) return "练习击球前压重心和蹬地启动。";
        return "先练脚下到位、降重心、蹬转带拍。";
    }

    private String recoveryTimeInsight(StrokeScore stroke) {
        if (!stroke.stableFrameFound) {
            return "击球后没有很快进入稳定状态，衔接下一拍会吃亏。";
        }
        if (stroke.recoveryTimeScore >= 88) return "击球后很快稳定，衔接意识很好。";
        if (stroke.recoveryTimeScore >= 78) return "回位速度较好，能比较快重新准备。";
        if (stroke.recoveryTimeScore >= 65) return "恢复基本可用，但还有压缩停顿的空间。";
        if (stroke.recoveryTimeScore >= 50) return "恢复偏慢，下一拍准备容易晚。";
        return "恢复明显偏慢，动作完成后容易停在原地。";
    }

    private String recoveryTimeAdvice(StrokeScore stroke) {
        if (stroke.recoveryTimeScore >= 78) return "保持打完后马上找下一拍。";
        if (stroke.recoveryTimeScore >= 65) return "缩短随挥后的停顿，脚下保持小调整。";
        return "先练打完即收拍、回中、举拍的固定节奏。";
    }

    private String residualInsight(double score) {
        if (score >= 88) return "随挥后动作很干净，身体控制好。";
        if (score >= 78) return "多余摆动较少，动作能收住。";
        if (score >= 65) return "动作收束基本可用，但还有残余摆动。";
        if (score >= 50) return "随挥后身体仍在摆动，影响重新启动。";
        return "多余动作偏大，击球后的身体控制不足。";
    }

    private String residualAdvice(double score) {
        if (score >= 78) return "继续保持发力后收住重心。";
        if (score >= 65) return "练习随挥结束后立刻回到准备姿态。";
        return "减少打完后的大幅摆动，先把重心稳住。";
    }

    private String postureInsight(double score) {
        if (score >= 88) return "身体回正很快，准备姿态恢复好。";
        if (score >= 78) return "肩髋能较快回正，下一拍准备不错。";
        if (score >= 65) return "身体有回正趋势，但还不够干净。";
        if (score >= 50) return "身体回正偏慢，容易影响下一拍启动方向。";
        return "身体姿态恢复不足，击球后容易散掉。";
    }

    private String postureAdvice(double score) {
        if (score >= 78) return "保持击球后面向来球方向的准备姿态。";
        if (score >= 65) return "打完后主动收拍、降重心、调整朝向。";
        return "先练每拍结束都回到中立准备姿态。";
    }

    private String timingSuggestion(StrokeScore stroke) {
        if (stroke.timing >= 78) return "继续保持提前准备，把主动击球点稳定下来。";
        if (stroke.heightScore < stroke.prepScore) return "优先把移动和架拍提前，争取在更主动的位置触球。";
        if (stroke.prepScore < stroke.elbowScore) return "优先练击球前准备，不要等球到身前才启动。";
        return "先把击球前的空间和节奏做出来，再追求更强发力。";
    }

    private String chainSuggestion(StrokeScore stroke) {
        if (stroke.chain >= 78) return "发力链质量不错，下一步可以追求更稳定的连续多拍输出。";
        if (stroke.kneeLoadScore < 60) return "优先加强下肢加载，让蹬转先于手臂发力。";
        if (stroke.orderScore < 60) return "优先拆开发力顺序，用慢动作练身体带动拍头。";
        if (stroke.wristLateScore < 60) return "优先把手腕释放集中到触球瞬间。";
        return "保留完整挥拍轨迹，同时提升最后一段加速。";
    }

    private String recoverySuggestion(StrokeScore stroke) {
        if (stroke.recovery >= 78) return "恢复质量较好，可以继续追求更快衔接下一拍。";
        if (!stroke.stableFrameFound || stroke.recoveryTimeScore < 60) return "优先练打完马上回中和举拍，减少击球后的停顿。";
        if (stroke.residualScore < 60) return "优先减少随挥后的多余摆动，把身体收住。";
        return "优先让肩髋和重心更快回到准备姿态。";
    }

    private static final class EvidenceReport {
        final String title;
        final double score;
        final String summary;
        final String suggestion;
        final List<EvidenceItem> items = new ArrayList<>();
        double confidenceScore;

        EvidenceReport(String title, double score, String summary, String suggestion) {
            this.title = title;
            this.score = score;
            this.summary = summary;
            this.suggestion = suggestion;
        }
    }

    private static final class EvidenceItem {
        final String name;
        final double score;
        final String insight;
        final String advice;

        EvidenceItem(String name, double score, String insight, String advice) {
            this.name = name;
            this.score = score;
            this.insight = insight;
            this.advice = advice;
        }
    }

    private String buildScoreSummary(ReviewSummary summary) {
        return "击球时机 " + format(summary.avgTiming)
                + " / 发力链 " + format(summary.avgChain)
                + " / 回位 " + format(summary.avgRecovery);
    }

    private String buildPerformanceSummary(
            int durationMs,
            int width,
            int height,
            int scaledWidth,
            int scaledHeight,
            List<FramePose> frames,
            long elapsedMs,
            long decodeMs,
            long poseMs,
            long scoreMs
    ) {
        int poseFrames = countPoseFrames(frames);
        double coverage = frames.isEmpty() ? 0 : poseFrames * 100.0 / frames.size();
        double fps = elapsedMs <= 0 ? 0 : frames.size() * 1000.0 / elapsedMs;
        return "视频 " + width + "x" + height + " · " + format(durationMs / 1000.0) + "s"
                + "\n模型输入 " + scaledWidth + "x" + scaledHeight
                + " · 覆盖 " + format(coverage) + "% (" + poseFrames + "/" + frames.size() + ")"
                + "\n端上耗时 " + format(elapsedMs / 1000.0) + "s"
                + " · 吞吐 " + format(fps) + " sampled fps"
                + "\ndecode " + format(decodeMs / 1000.0) + "s"
                + " · pose " + format(poseMs / 1000.0) + "s"
                + " · score " + format(scoreMs / 1000.0) + "s";
    }

    private String buildEventSummary(ReviewSummary summary) {
        if (summary.events.isEmpty()) {
            return "没有检测到足够明显的重发力窗口。建议换用近端球员清晰、身体完整入镜的视频。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("检测到 ").append(summary.events.size()).append(" 个重发力窗口");
        int limit = Math.min(summary.events.size(), 8);
        for (int i = 0; i < limit; i++) {
            StrokeScore stroke = summary.events.get(i);
            builder.append("\n#").append(stroke.index + 1)
                    .append("  t=").append(format(stroke.timeSec)).append("s")
                    .append("  ").append(stroke.side)
                    .append("  timing ").append(format(stroke.timing))
                    .append("  chain ").append(format(stroke.chain))
                    .append("  recovery ").append(format(stroke.recovery));
        }
        if (summary.events.size() > limit) {
            builder.append("\n还有 ").append(summary.events.size() - limit).append(" 个窗口见下方明细。");
        }
        return builder.toString();
    }

    private int countPoseFrames(List<FramePose> frames) {
        int poseFrames = 0;
        for (FramePose frame : frames) {
            if (frame.hasPose()) {
                poseFrames++;
            }
        }
        return poseFrames;
    }

    private String buildReport(
            String sourceLabel,
            int durationMs,
            int width,
            int height,
            int scaledWidth,
            int scaledHeight,
            List<FramePose> frames,
            long elapsedMs,
            long decodeMs,
            long poseMs,
            long scoreMs,
            ReviewSummary summary
    ) {
        int poseFrames = 0;
        for (FramePose frame : frames) {
            if (frame.hasPose()) {
                poseFrames++;
            }
        }
        double coverage = frames.isEmpty() ? 0 : poseFrames * 100.0 / frames.size();
        double fps = elapsedMs <= 0 ? 0 : frames.size() * 1000.0 / elapsedMs;

        StringBuilder builder = new StringBuilder();
        builder.append("端上计算完成\n\n");
        builder.append("视频来源:\n").append(sourceLabel).append("\n\n");
        builder.append("视频信息: ")
                .append(width).append("x").append(height)
                .append(", ").append(format(durationMs / 1000.0)).append("s\n");
        builder.append("处理设置: 最多 60s, ").append(TARGET_FPS).append("fps 抽样\n");
        builder.append("模型输入: ").append(scaledWidth).append("x").append(scaledHeight).append("\n");
        builder.append("端上耗时: ").append(format(elapsedMs / 1000.0)).append("s\n");
        builder.append("耗时拆分: decode=").append(format(decodeMs / 1000.0))
                .append("s, pose=").append(format(poseMs / 1000.0))
                .append("s, score=").append(format(scoreMs / 1000.0)).append("s\n");
        builder.append("推理吞吐: ").append(format(fps)).append(" sampled fps\n");
        builder.append("Pose 覆盖: ").append(format(coverage)).append("% (")
                .append(poseFrames).append("/").append(frames.size()).append(")\n\n");

        builder.append("检测到重发力窗口: ").append(summary.events.size()).append("\n");
        builder.append("平均击球时机: ").append(format(summary.avgTiming)).append("/100\n");
        builder.append("平均发力链: ").append(format(summary.avgChain)).append("/100\n");
        builder.append("平均回位恢复: ").append(format(summary.avgRecovery)).append("/100\n\n");

        if (summary.events.isEmpty()) {
            builder.append("没有检测到足够明显的重发力窗口。建议选择近端球员清晰、5-20 秒、身体完整入镜的视频。\n");
        } else {
            for (StrokeScore stroke : summary.events) {
                builder.append("#").append(stroke.index + 1)
                        .append("  t=").append(format(stroke.timeSec)).append("s")
                        .append("  side=").append(stroke.side)
                        .append("\n  timing=").append(format(stroke.timing))
                        .append("  chain=").append(format(stroke.chain))
                        .append("  recovery=").append(format(stroke.recovery))
                        .append("\n");
            }
        }
        builder.append("\n说明: 这是端上 PoC。当前用 ")
                .append(TARGET_FPS)
                .append("fps 抽样验证手机推理和评分闭环；正式版需要与 Python 30fps 输出做 golden parity。");
        return builder.toString();
    }

    private String buildLongPoseReport(
            String sourceLabel,
            int durationMs,
            int processDurationMs,
            int width,
            int height,
            int scaledWidth,
            int scaledHeight,
            int targetFps,
            List<FramePose> frames,
            long elapsedMs,
            long decodeMs,
            long poseMs
    ) {
        int poseFrames = 0;
        for (FramePose frame : frames) {
            if (frame.hasPose()) {
                poseFrames++;
            }
        }
        double coverage = frames.isEmpty() ? 0 : poseFrames * 100.0 / frames.size();
        double fps = elapsedMs <= 0 ? 0 : frames.size() * 1000.0 / elapsedMs;

        StringBuilder builder = new StringBuilder();
        builder.append("长视频骨架渲染完成\n\n");
        builder.append("视频来源:\n").append(sourceLabel).append("\n\n");
        builder.append("视频信息: ")
                .append(width).append("x").append(height)
                .append(", 原始 ").append(format(durationMs / 1000.0)).append("s")
                .append(", 处理 ").append(format(processDurationMs / 1000.0)).append("s\n");
        builder.append("处理设置: 最多 30 分钟, ").append(targetFps).append("fps 抽样\n");
        builder.append("模型输入: ").append(scaledWidth).append("x").append(scaledHeight).append("\n");
        builder.append("端上耗时: ").append(format(elapsedMs / 1000.0)).append("s\n");
        builder.append("耗时拆分: decode=").append(format(decodeMs / 1000.0))
                .append("s, pose=").append(format(poseMs / 1000.0)).append("s\n");
        builder.append("推理吞吐: ").append(format(fps)).append(" sampled fps\n");
        builder.append("Pose 覆盖: ").append(format(coverage)).append("% (")
                .append(poseFrames).append("/").append(frames.size()).append(")\n\n");
        builder.append("说明: 长视频模式只生成全程骨架标注，用于快速复盘整段训练或对抗。")
                .append("此模式不计算重发力窗口、慢动作和动作证据评分。");
        return builder.toString();
    }

    private interface VideoSource {
        void configure(MediaMetadataRetriever retriever) throws Exception;

        AssetFileDescriptor open() throws Exception;

        String label();

        Uri previewUri();

        String previewPath();
    }

    private static final class DecodeStats {
        long decodeMs;
        long poseMs;
    }

    private DecodeStats decodeWithCodec(
            VideoSource source,
            PoseLandmarker landmarker,
            int processDurationMs,
            int stepMs,
            int expectedFrames,
            int targetFps,
            boolean longMode,
            int originalWidth,
            int originalHeight,
            int rotationDegrees,
            int durationMs,
            int scaledWidth,
            int scaledHeight,
            List<FramePose> frames
    ) throws Exception {
        DecodeStats stats = new DecodeStats();
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try (AssetFileDescriptor afd = source.open()) {
            if (afd == null) {
                throw new IllegalStateException("cannot open video source");
            }
            extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                throw new IllegalStateException("video track not found");
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IllegalStateException("video mime not found");
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long nextTargetUs = 0L;
            long processDurationUs = processDurationMs * 1000L;
            long stepUs = stepMs * 1000L;
            int processed = 0;

            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        java.nio.ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            continue;
                        }
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleSize < 0 || sampleTimeUs > processDurationUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, extractor.getSampleFlags());
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                }
                if (outputIndex < 0) {
                    continue;
                }

                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0 && info.presentationTimeUs >= nextTargetUs && info.presentationTimeUs <= processDurationUs) {
                    long decodeStart = SystemClock.elapsedRealtime();
                    Bitmap bitmap = imageToScaledBitmap(codec.getOutputImage(outputIndex), scaledWidth, scaledHeight, rotationDegrees);
                    stats.decodeMs += SystemClock.elapsedRealtime() - decodeStart;
                    if (bitmap != null) {
                        int timestampMs = (int) Math.max(0, info.presentationTimeUs / 1000L);
                        MPImage image = new BitmapImageBuilder(bitmap).build();
                        long poseStart = SystemClock.elapsedRealtime();
                        PoseLandmarkerResult result = landmarker.detectForVideo(image, timestampMs);
                        stats.poseMs += SystemClock.elapsedRealtime() - poseStart;
                        frames.add(FramePose.fromResult(timestampMs / 1000.0, bitmap.getWidth(), bitmap.getHeight(), result));
                        bitmap.recycle();
                        processed++;
                        nextTargetUs += stepUs;
                        while (nextTargetUs <= info.presentationTimeUs) {
                            nextTargetUs += stepUs;
                        }
                        if (processed == 1 || processed % 15 == 0) {
                            int pct = Math.min(99, Math.round(processed * 100f / expectedFrames));
                            int shownProcessed = processed;
                            runOnUiThread(() -> {
                                progress.setProgress(pct);
                                progressTitle.setText(longMode ? "正在生成骨架" : "正在分析动作");
                                progressBody.setText("视频 " + originalWidth + "x" + originalHeight
                                        + " · 模型输入 " + scaledWidth + "x" + scaledHeight
                                        + "\n" + targetFps + "fps 抽样，已处理 " + shownProcessed
                                        + " 帧 · " + pct + "%");
                                output.setText("端上计算中...\n"
                                        + "视频: " + originalWidth + "x" + originalHeight + ", " + format(durationMs / 1000.0) + "s\n"
                                        + "模型输入: " + scaledWidth + "x" + scaledHeight + "\n"
                                        + "抽样: " + targetFps + "fps, 已处理帧: " + shownProcessed + "\n"
                                        + "当前进度: " + pct + "%");
                            });
                        }
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false);
                if (eos || info.presentationTimeUs > processDurationUs) {
                    outputDone = true;
                }
            }
        } finally {
            extractor.release();
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        }
        return stats;
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private static Bitmap imageToScaledBitmap(Image image, int targetWidth, int targetHeight, int rotationDegrees) {
        if (image == null) {
            return null;
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            return null;
        }
        Rect crop = image.getCropRect();
        if (crop == null || crop.width() <= 0 || crop.height() <= 0) {
            crop = new Rect(0, 0, image.getWidth(), image.getHeight());
        }
        int sourceWidth = crop.width();
        int sourceHeight = crop.height();
        java.nio.ByteBuffer yBuffer = planes[0].getBuffer();
        java.nio.ByteBuffer uBuffer = planes[1].getBuffer();
        java.nio.ByteBuffer vBuffer = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        int[] pixels = new int[targetWidth * targetHeight];

        for (int ty = 0; ty < targetHeight; ty++) {
            float dy = targetHeight <= 1 ? 0f : ty / (float) (targetHeight - 1);
            int rowOut = ty * targetWidth;
            for (int tx = 0; tx < targetWidth; tx++) {
                float dx = targetWidth <= 1 ? 0f : tx / (float) (targetWidth - 1);
                int sx;
                int sy;
                switch (rotationDegrees) {
                    case 90:
                        sx = crop.left + Math.round(dy * (sourceWidth - 1));
                        sy = crop.top + Math.round((1f - dx) * (sourceHeight - 1));
                        break;
                    case 180:
                        sx = crop.left + Math.round((1f - dx) * (sourceWidth - 1));
                        sy = crop.top + Math.round((1f - dy) * (sourceHeight - 1));
                        break;
                    case 270:
                        sx = crop.left + Math.round((1f - dy) * (sourceWidth - 1));
                        sy = crop.top + Math.round(dx * (sourceHeight - 1));
                        break;
                    default:
                        sx = crop.left + Math.round(dx * (sourceWidth - 1));
                        sy = crop.top + Math.round(dy * (sourceHeight - 1));
                        break;
                }
                sx = Math.max(crop.left, Math.min(crop.right - 1, sx));
                sy = Math.max(crop.top, Math.min(crop.bottom - 1, sy));
                int yRow = sy * yRowStride;
                int uRow = (sy / 2) * uRowStride;
                int vRow = (sy / 2) * vRowStride;
                int y = yBuffer.get(yRow + sx * yPixelStride) & 0xff;
                int u = uBuffer.get(uRow + (sx / 2) * uPixelStride) & 0xff;
                int v = vBuffer.get(vRow + (sx / 2) * vPixelStride) & 0xff;
                pixels[rowOut + tx] = yuvToArgb(y, u, v);
            }
        }
        return Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
    }

    private static int yuvToArgb(int y, int u, int v) {
        int c = Math.max(0, y - 16);
        int d = u - 128;
        int e = v - 128;
        int r = clampColor((298 * c + 409 * e + 128) >> 8);
        int g = clampColor((298 * c - 100 * d - 208 * e + 128) >> 8);
        int b = clampColor((298 * c + 516 * d + 128) >> 8);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int parseInt(String value, int fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String stackTraceText(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        builder.append(throwable.getClass().getSimpleName()).append(": ").append(throwable.getMessage()).append("\n");
        for (StackTraceElement element : throwable.getStackTrace()) {
            builder.append("  at ").append(element).append("\n");
            if (builder.length() > 5000) {
                builder.append("  ...");
                break;
            }
        }
        return builder.toString();
    }

    private static final class DrainResult {
        final boolean muxerStarted;
        final int trackIndex;

        DrainResult(boolean muxerStarted, int trackIndex) {
            this.muxerStarted = muxerStarted;
            this.trackIndex = trackIndex;
        }
    }

    private static final class CachedReview implements Serializable {
        private static final long serialVersionUID = 1L;
        final String key;
        final String sourceLabel;
        final String videoPath;
        final int durationMs;
        final int width;
        final int height;
        final int scaledWidth;
        final int scaledHeight;
        final boolean longMode;
        final int sampleFps;
        final ArrayList<FramePose> frames;
        final ReviewSummary summary;
        final String report;
        final String scoreText;
        final String performanceText;
        final String eventText;

        CachedReview(
                String key,
                String sourceLabel,
                String videoPath,
                int durationMs,
                int width,
                int height,
                int scaledWidth,
                int scaledHeight,
                boolean longMode,
                int sampleFps,
                ArrayList<FramePose> frames,
                ReviewSummary summary,
                String report,
                String scoreText,
                String performanceText,
                String eventText
        ) {
            this.key = key;
            this.sourceLabel = sourceLabel;
            this.videoPath = videoPath;
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.scaledWidth = scaledWidth;
            this.scaledHeight = scaledHeight;
            this.longMode = longMode;
            this.sampleFps = sampleFps;
            this.frames = frames;
            this.summary = summary;
            this.report = report;
            this.scoreText = scoreText;
            this.performanceText = performanceText;
            this.eventText = eventText;
        }
    }

    private static final class Point2 implements Serializable {
        private static final long serialVersionUID = 1L;
        final double x;
        final double y;
        final double visibility;

        Point2(double x, double y, double visibility) {
            this.x = x;
            this.y = y;
            this.visibility = visibility;
        }
    }

    private static final class Point3 implements Serializable {
        private static final long serialVersionUID = 1L;
        final double x;
        final double y;
        final double z;
        final double visibility;

        Point3(double x, double y, double z, double visibility) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.visibility = visibility;
        }
    }

    private static final class FramePose implements Serializable {
        private static final long serialVersionUID = 1L;
        final double timeSec;
        final int width;
        final int height;
        final Point2[] pose;
        final Point3[] worldPose;

        FramePose(double timeSec, int width, int height, Point2[] pose, Point3[] worldPose) {
            this.timeSec = timeSec;
            this.width = width;
            this.height = height;
            this.pose = pose;
            this.worldPose = worldPose;
        }

        static FramePose fromResult(double timeSec, int width, int height, PoseLandmarkerResult result) {
            Point2[] pose = new Point2[33];
            Point3[] worldPose = new Point3[33];
            if (result != null && !result.landmarks().isEmpty()) {
                List<NormalizedLandmark> landmarks = result.landmarks().get(0);
                int count = Math.min(33, landmarks.size());
                for (int i = 0; i < count; i++) {
                    NormalizedLandmark lm = landmarks.get(i);
                    float visibility = lm.visibility().orElse(1.0f);
                    pose[i] = new Point2(lm.x() * width, lm.y() * height, visibility);
                }
            }
            if (result != null && !result.worldLandmarks().isEmpty()) {
                List<Landmark> landmarks = result.worldLandmarks().get(0);
                int count = Math.min(33, landmarks.size());
                for (int i = 0; i < count; i++) {
                    Landmark lm = landmarks.get(i);
                    double visibility = lm.visibility().orElse(1.0f);
                    worldPose[i] = new Point3(lm.x(), lm.y(), lm.z(), visibility);
                }
            }
            return new FramePose(timeSec, width, height, pose, worldPose);
        }

        boolean hasPose() {
            for (Point2 point : pose) {
                if (point != null) {
                    return true;
                }
            }
            return false;
        }

        boolean hasWorldPose() {
            if (worldPose == null) {
                return false;
            }
            for (Point3 point : worldPose) {
                if (point != null) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class PoseOverlayView extends View {
        private static final int[][] BONES = new int[][]{
                {11, 12}, {11, 13}, {13, 15}, {12, 14}, {14, 16},
                {11, 23}, {12, 24}, {23, 24}, {23, 25}, {25, 27},
                {24, 26}, {26, 28}, {27, 31}, {28, 32}
        };

        private final Paint bonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint jointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF fit = new RectF();
        private final Runnable refreshLoop = new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow() || frames.isEmpty()) {
                    return;
                }
                invalidate();
                long delayMs = video != null && video.isPlaying() ? 33L : 120L;
                postDelayed(this, delayMs);
            }
        };
        private VideoView video;
        private List<FramePose> frames = Collections.emptyList();

        PoseOverlayView(Context context) {
            super(context);
            setWillNotDraw(false);
            bonePaint.setColor(0xff9BF7C5);
            bonePaint.setStrokeWidth(5f);
            bonePaint.setStrokeCap(Paint.Cap.ROUND);
            bonePaint.setStyle(Paint.Style.STROKE);
            jointPaint.setColor(0xffF7D76E);
            jointPaint.setStyle(Paint.Style.FILL);
            glowPaint.setColor(0x5520C878);
            glowPaint.setStrokeWidth(10f);
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStyle(Paint.Style.STROKE);
        }

        void bind(VideoView video, List<FramePose> frames) {
            this.video = video;
            if (frames == null || frames.isEmpty()) {
                this.frames = Collections.emptyList();
            } else {
                ArrayList<FramePose> sorted = new ArrayList<>(frames);
                Collections.sort(sorted, Comparator.comparingDouble(frame -> frame.timeSec));
                this.frames = sorted;
            }
            refreshNow();
            startRefreshLoop();
        }

        void refreshNow() {
            invalidate();
            startRefreshLoop();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            startRefreshLoop();
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(refreshLoop);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            FramePose frame = currentFrame();
            if (frame == null || !frame.hasPose()) {
                return;
            }
            computeFit(frame);
            for (int[] bone : BONES) {
                Point2 a = visible(frame, bone[0]);
                Point2 b = visible(frame, bone[1]);
                if (a == null || b == null) {
                    continue;
                }
                float ax = mapX(a.x, frame);
                float ay = mapY(a.y, frame);
                float bx = mapX(b.x, frame);
                float by = mapY(b.y, frame);
                canvas.drawLine(ax, ay, bx, by, glowPaint);
                canvas.drawLine(ax, ay, bx, by, bonePaint);
            }
            for (Point2 point : frame.pose) {
                if (point == null || point.visibility < 0.35) {
                    continue;
                }
                canvas.drawCircle(mapX(point.x, frame), mapY(point.y, frame), 4.5f, jointPaint);
            }
        }

        private void startRefreshLoop() {
            removeCallbacks(refreshLoop);
            if (!frames.isEmpty() && isAttachedToWindow()) {
                postDelayed(refreshLoop, video != null && video.isPlaying() ? 33L : 120L);
            }
        }

        private FramePose currentFrame() {
            if (frames.isEmpty()) {
                return null;
            }
            double sec = video == null ? frames.get(0).timeSec : video.getCurrentPosition() / 1000.0;
            if (sec <= frames.get(0).timeSec) {
                return frames.get(0);
            }
            FramePose last = frames.get(frames.size() - 1);
            if (sec >= last.timeSec) {
                return last;
            }
            FramePose prev = frames.get(0);
            for (int i = 1; i < frames.size(); i++) {
                FramePose next = frames.get(i);
                if (next.timeSec >= sec) {
                    return interpolate(prev, next, sec);
                }
                prev = next;
            }
            return last;
        }

        private static FramePose interpolate(FramePose a, FramePose b, double sec) {
            double span = Math.max(0.001, b.timeSec - a.timeSec);
            if (span > 0.25) {
                return Math.abs(sec - a.timeSec) <= Math.abs(b.timeSec - sec) ? a : b;
            }
            double t = Math.max(0.0, Math.min(1.0, (sec - a.timeSec) / span));
            Point2[] pose = new Point2[33];
            for (int i = 0; i < pose.length; i++) {
                Point2 pa = i < a.pose.length ? a.pose[i] : null;
                Point2 pb = i < b.pose.length ? b.pose[i] : null;
                pose[i] = interpolatePoint(pa, pb, t);
            }
            int width = a.width > 0 ? a.width : b.width;
            int height = a.height > 0 ? a.height : b.height;
            return new FramePose(sec, width, height, pose, null);
        }

        private static Point2 interpolatePoint(Point2 a, Point2 b, double t) {
            if (a == null && b == null) {
                return null;
            }
            if (a == null) {
                return new Point2(b.x, b.y, b.visibility * 0.65);
            }
            if (b == null) {
                return new Point2(a.x, a.y, a.visibility * 0.65);
            }
            double visibility = a.visibility * (1.0 - t) + b.visibility * t;
            return new Point2(
                    a.x * (1.0 - t) + b.x * t,
                    a.y * (1.0 - t) + b.y * t,
                    visibility
            );
        }

        private void computeFit(FramePose frame) {
            float viewW = Math.max(1, video != null && video.getWidth() > 0 ? video.getWidth() : getWidth());
            float viewH = Math.max(1, video != null && video.getHeight() > 0 ? video.getHeight() : getHeight());
            float viewLeft = video != null ? video.getLeft() : 0f;
            float viewTop = video != null ? video.getTop() : 0f;
            float srcW = Math.max(1, frame.width);
            float srcH = Math.max(1, frame.height);
            float scale = Math.min(viewW / srcW, viewH / srcH);
            float drawW = srcW * scale;
            float drawH = srcH * scale;
            fit.set(viewLeft + (viewW - drawW) / 2f, viewTop + (viewH - drawH) / 2f,
                    viewLeft + (viewW + drawW) / 2f, viewTop + (viewH + drawH) / 2f);
        }

        private Point2 visible(FramePose frame, int index) {
            if (index < 0 || index >= frame.pose.length) {
                return null;
            }
            Point2 point = frame.pose[index];
            return point != null && point.visibility >= 0.35 ? point : null;
        }

        private float mapX(double x, FramePose frame) {
            return fit.left + (float) (x / Math.max(1, frame.width)) * fit.width();
        }

        private float mapY(double y, FramePose frame) {
            return fit.top + (float) (y / Math.max(1, frame.height)) * fit.height();
        }
    }

    private static final class MetricFrame {
        int frame;
        double timeSec;
        String side;
        double wristSpeed;
        double leftWristSpeed;
        double rightWristSpeed;
        double normalizedWristSpeed;
        Double elbowAngle;
        Double kneeAngle;
        Double twist;
        Double wristAboveShoulder;
        Double torso;
        double armConfidence;
        double elbowAngularSpeed;
        double kneeAngularSpeed;
    }

    private static final class StrokeScore implements Serializable {
        private static final long serialVersionUID = 2L;
        int index;
        int frame;
        double timeSec;
        String side;
        double timing;
        double chain;
        double recovery;
        double overall;
        String actionLevel;
        double heightScore;
        double elbowScore;
        double prepScore;
        double confidenceScore;
        double maxHeightRatio;
        double contactHeightRatio;
        double elbowAngle;
        double prepHeightRatio;
        double twistScore;
        double energyScore;
        double orderScore;
        double wristLateScore;
        double kneeLoadScore;
        double legEnergy;
        double trunkEnergy;
        double elbowEnergy;
        double wristEnergy;
        double kneeLoad;
        double recoverySeconds;
        double recoveryTimeScore;
        double residualScore;
        double postureScore;
        double recoveryMedianSpeed;
        double recoveryMedianTwist;
        boolean stableFrameFound;
    }

    private static final class ReviewSummary implements Serializable {
        private static final long serialVersionUID = 1L;
        final List<StrokeScore> events;
        final double avgTiming;
        final double avgChain;
        final double avgRecovery;

        ReviewSummary(List<StrokeScore> events) {
            this.events = events;
            this.avgTiming = average(events, "timing");
            this.avgChain = average(events, "chain");
            this.avgRecovery = average(events, "recovery");
        }

        private static double average(List<StrokeScore> events, String field) {
            if (events.isEmpty()) {
                return 0.0;
            }
            double sum = 0.0;
            for (StrokeScore event : events) {
                if ("timing".equals(field)) sum += event.timing;
                if ("chain".equals(field)) sum += event.chain;
                if ("recovery".equals(field)) sum += event.recovery;
            }
            return sum / events.size();
        }
    }

    private static final class PoseScorer {
        private static final int[] LEFT_ARM = {11, 13, 15};
        private static final int[] RIGHT_ARM = {12, 14, 16};
        private static final int[] LEFT_LEG = {23, 25, 27};
        private static final int[] RIGHT_LEG = {24, 26, 28};

        static ReviewSummary score(List<FramePose> rawFrames, double fps) {
            List<Point2> leftWrist = new ArrayList<>();
            List<Point2> rightWrist = new ArrayList<>();
            for (FramePose frame : rawFrames) {
                leftWrist.add(point(frame, 15));
                rightWrist.add(point(frame, 16));
            }
            List<Point2> smoothLeft = smooth(leftWrist, 0.62);
            List<Point2> smoothRight = smooth(rightWrist, 0.62);
            double[] leftSpeed = speed(smoothLeft, fps);
            double[] rightSpeed = speed(smoothRight, fps);
            double[] actionScores = actionScores(rawFrames, leftSpeed, rightSpeed);
            List<Integer> events = robustEvents(actionScores, fps, 6);

            List<MetricFrame> records = buildRecords(rawFrames, fps, leftSpeed, rightSpeed);
            enrichAngularSpeeds(records, fps);

            List<StrokeScore> strokes = new ArrayList<>();
            int idx = 0;
            for (Integer eventFrame : events) {
                int start = Math.max(0, (int) Math.round(eventFrame - fps * 1.05));
                int end = Math.min(records.size() - 1, (int) Math.round(eventFrame + fps * 0.10));
                StrokeScore stroke = scoreStroke(records, eventFrame, start, end, fps);
                stroke.index = idx++;
                strokes.add(stroke);
            }
            return new ReviewSummary(strokes);
        }

        private static List<MetricFrame> buildRecords(List<FramePose> frames, double fps, double[] leftSpeed, double[] rightSpeed) {
            List<MetricFrame> out = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                FramePose frame = frames.get(i);
                String side = activeSide(frame, leftSpeed[i], rightSpeed[i]);
                int[] arm = "left".equals(side) ? LEFT_ARM : RIGHT_ARM;
                int[] leg = "left".equals(side) ? LEFT_LEG : RIGHT_LEG;
                Point2 sh = point(frame, arm[0]);
                Point2 el = point(frame, arm[1]);
                Point2 wr = point(frame, arm[2]);
                Point2 hp = point(frame, leg[0]);
                Point2 kn = point(frame, leg[1]);
                Point2 an = point(frame, leg[2]);

                MetricFrame metric = new MetricFrame();
                metric.frame = i;
                metric.timeSec = frame.timeSec;
                metric.side = side;
                metric.leftWristSpeed = leftSpeed[i];
                metric.rightWristSpeed = rightSpeed[i];
                metric.wristSpeed = Math.max(leftSpeed[i], rightSpeed[i]);
                metric.elbowAngle = angle(sh, el, wr);
                metric.kneeAngle = angle(hp, kn, an);
                metric.wristAboveShoulder = sh != null && wr != null ? sh.y - wr.y : null;
                metric.twist = twist(frame);
                metric.torso = torso(frame);
                metric.normalizedWristSpeed = metric.wristSpeed / Math.max(metric.torso != null ? metric.torso : 65.0, 45.0);
                metric.armConfidence = Math.min(Math.min(visibility(frame, arm[0]), visibility(frame, arm[1])), visibility(frame, arm[2]));
                out.add(metric);
            }
            return out;
        }

        private static StrokeScore scoreStroke(List<MetricFrame> records, int eventIdx, int start, int end, double fps) {
            MetricFrame contact = records.get(eventIdx);
            int contactEnd = Math.min(records.size() - 1, (int) Math.round(eventIdx + fps * 0.10));
            int recoverStart = Math.min(records.size() - 1, (int) Math.round(eventIdx + fps * 0.12));
            int recoverEnd = Math.min(records.size() - 1, (int) Math.round(eventIdx + fps * 1.20));

            List<MetricFrame> pre = records.subList(start, Math.min(eventIdx + 1, records.size()));
            List<MetricFrame> strokeWindow = records.subList(start, Math.min(contactEnd + 1, records.size()));
            List<MetricFrame> recover = records.subList(recoverStart, Math.min(recoverEnd + 1, records.size()));

            double torso = Math.max(medianD(strokeWindow, "torso", 65.0), 45.0);
            double armConf = medianD(strokeWindow, "armConfidence", 0.45);
            double confidenceScore = clamp((armConf - 0.25) / 0.65 * 100.0);
            double maxHeightRatio = maxD(strokeWindow, "wristAboveShoulder", 0.0) / torso;
            double contactHeightRatio = safe(contact.wristAboveShoulder, 0.0) / torso;
            double maxHeightScore = scoreRange(maxHeightRatio, -0.15, 0.95);
            double contactHeightScore = scoreRange(contactHeightRatio, -0.35, 0.75);
            double heightScore = 0.55 * maxHeightScore + 0.45 * contactHeightScore;
            double elbowScore = scoreBand(contact.elbowAngle, 145.0, 70.0);
            int prepCut = Math.max(start, (int) Math.round(eventIdx - fps * 0.38));
            List<MetricFrame> prep = records.subList(start, Math.min(prepCut + 1, records.size()));
            double prepHeightRatio = maxD(prep, "wristAboveShoulder", -torso) / torso;
            double prepHeightScore = scoreRange(prepHeightRatio, -0.35, 0.80);
            double twistScore = scoreRange(maxD(pre, "twist", 0.0), 4.0, 24.0);
            double prepScore = 0.62 * prepHeightScore + 0.38 * twistScore;
            double timing = clamp(0.46 * heightScore + 0.24 * elbowScore + 0.20 * prepScore + 0.10 * confidenceScore);

            List<MetricFrame> legBand = band(records, eventIdx, fps, -0.65, -0.22);
            List<MetricFrame> trunkArmBand = band(records, eventIdx, fps, -0.38, -0.06);
            List<MetricFrame> wristBand = band(records, eventIdx, fps, -0.18, 0.08);
            double legEnergy = percentileD(legBand, "kneeAngularSpeed", 80, 0.0);
            double trunkEnergy = twistEnergy(trunkArmBand, fps);
            double elbowEnergy = percentileD(trunkArmBand, "elbowAngularSpeed", 82, 0.0);
            double wristEnergy = percentileD(wristBand, "normalizedWristSpeed", 88, 0.0);
            double energyScore = clamp(
                    0.20 * scoreRange(legEnergy, 90.0, 520.0)
                            + 0.18 * scoreRange(trunkEnergy, 35.0, 260.0)
                            + 0.24 * scoreRange(elbowEnergy, 220.0, 1350.0)
                            + 0.38 * scoreRange(wristEnergy, 2.2, 10.5)
            );
            double orderScore = orderScore(legBand, trunkArmBand, wristBand, fps);
            int wristPeak = peakFrame(records.subList(start, Math.min(contactEnd + 1, records.size())), "normalizedWristSpeed", eventIdx);
            double wristLate = scoreRange(Math.abs(wristPeak - eventIdx) / Math.max(fps, 1.0), 0.38, 0.02);
            double kneeLoad = 0.0;
            for (MetricFrame item : pre) {
                if (item.kneeAngle != null) kneeLoad = Math.max(kneeLoad, Math.max(0.0, 180.0 - item.kneeAngle));
            }
            double kneeLoadScore = scoreRange(kneeLoad, 12.0, 72.0);
            double chain = clamp(0.32 * energyScore + 0.26 * orderScore + 0.18 * wristLate + 0.14 * kneeLoadScore + 0.10 * confidenceScore);

            Integer stableFrame = null;
            for (MetricFrame item : recover) {
                if (item.normalizedWristSpeed <= 1.15 && item.elbowAngularSpeed <= 360.0 && item.kneeAngularSpeed <= 300.0) {
                    stableFrame = item.frame;
                    break;
                }
            }
            double recoverySeconds = stableFrame == null ? (recoverEnd - eventIdx) / Math.max(fps, 1.0) : Math.max(0.0, (stableFrame - eventIdx) / Math.max(fps, 1.0));
            double recoveryTimeScore = stableFrame == null ? 32.0 : scoreRange(recoverySeconds, 1.20, 0.28);
            double recoveryMedianSpeed = medianD(recover, "normalizedWristSpeed", 1.2);
            double recoveryMedianTwist = medianD(recover, "twist", 16.0);
            double residualScore = scoreRange(recoveryMedianSpeed, 1.70, 0.35);
            double postureScore = scoreRange(recoveryMedianTwist, 26.0, 5.0);
            double recovery = clamp(0.54 * recoveryTimeScore + 0.28 * residualScore + 0.18 * postureScore);

            StrokeScore stroke = new StrokeScore();
            stroke.frame = eventIdx;
            stroke.timeSec = contact.timeSec;
            stroke.side = contact.side;
            stroke.timing = timing;
            stroke.chain = chain;
            stroke.recovery = recovery;
            stroke.overall = actionOverall(timing, chain, recovery);
            stroke.actionLevel = actionLevel(stroke.overall);
            stroke.heightScore = heightScore;
            stroke.elbowScore = elbowScore;
            stroke.prepScore = prepScore;
            stroke.confidenceScore = confidenceScore;
            stroke.maxHeightRatio = maxHeightRatio;
            stroke.contactHeightRatio = contactHeightRatio;
            stroke.elbowAngle = safe(contact.elbowAngle, 0.0);
            stroke.prepHeightRatio = prepHeightRatio;
            stroke.twistScore = twistScore;
            stroke.energyScore = energyScore;
            stroke.orderScore = orderScore;
            stroke.wristLateScore = wristLate;
            stroke.kneeLoadScore = kneeLoadScore;
            stroke.legEnergy = legEnergy;
            stroke.trunkEnergy = trunkEnergy;
            stroke.elbowEnergy = elbowEnergy;
            stroke.wristEnergy = wristEnergy;
            stroke.kneeLoad = kneeLoad;
            stroke.recoverySeconds = recoverySeconds;
            stroke.recoveryTimeScore = recoveryTimeScore;
            stroke.residualScore = residualScore;
            stroke.postureScore = postureScore;
            stroke.recoveryMedianSpeed = recoveryMedianSpeed;
            stroke.recoveryMedianTwist = recoveryMedianTwist;
            stroke.stableFrameFound = stableFrame != null;
            return stroke;
        }

        private static double actionOverall(double timing, double chain, double recovery) {
            return clamp(0.35 * timing + 0.40 * chain + 0.25 * recovery);
        }

        private static String actionLevel(double score) {
            if (score < 40) return "待观察";
            if (score < 50) return "基础";
            if (score < 60) return "可训练";
            if (score < 70) return "较稳定";
            if (score < 80) return "良好";
            if (score < 88) return "优秀";
            return "高质量";
        }

        private static List<Integer> robustEvents(double[] values, double fps, int limit) {
            if (values.length < 5) return Collections.emptyList();
            double max = 0.0;
            List<Double> sorted = new ArrayList<>();
            for (double value : values) {
                max = Math.max(max, value);
                sorted.add(value);
            }
            if (max <= 0.0) return Collections.emptyList();
            Collections.sort(sorted);
            double p86 = sorted.get(Math.min(sorted.size() - 1, Math.max(0, (int) Math.floor(sorted.size() * 0.86))));
            double threshold = Math.max(p86, max * 0.38);
            int gap = (int) Math.max(14, fps * 0.75);
            List<int[]> candidates = new ArrayList<>();
            for (int i = 2; i < values.length - 2; i++) {
                if (values[i] >= threshold && values[i] >= values[i - 1] && values[i] >= values[i + 1]) {
                    candidates.add(new int[]{(int) Math.round(values[i] * 1000), i});
                }
            }
            candidates.sort((a, b) -> Integer.compare(b[0], a[0]));
            List<Integer> selected = new ArrayList<>();
            for (int[] candidate : candidates) {
                int frame = candidate[1];
                boolean farEnough = true;
                for (Integer old : selected) {
                    if (Math.abs(frame - old) < gap) {
                        farEnough = false;
                        break;
                    }
                }
                if (farEnough) selected.add(frame);
                if (selected.size() >= limit) break;
            }
            Collections.sort(selected);
            return selected;
        }

        private static double[] actionScores(List<FramePose> frames, double[] leftSpeed, double[] rightSpeed) {
            double[] scores = new double[frames.size()];
            for (int i = 0; i < frames.size(); i++) {
                FramePose frame = frames.get(i);
                scores[i] = Math.max(
                        sideAction(frame, "left", leftSpeed[i]),
                        sideAction(frame, "right", rightSpeed[i])
                );
            }
            return scores;
        }

        private static double sideAction(FramePose frame, String side, double speed) {
            int shoulder = "left".equals(side) ? 11 : 12;
            int elbow = "left".equals(side) ? 13 : 14;
            int wrist = "left".equals(side) ? 15 : 16;
            Point2 sh = point(frame, shoulder);
            Point2 wr = point(frame, wrist);
            double height = sh != null && wr != null ? sh.y - wr.y : 0.0;
            double conf = Math.min(Math.min(visibility(frame, shoulder), visibility(frame, elbow)), visibility(frame, wrist));
            double heightBonus = 1.0 + Math.max(0.0, height) / 50.0;
            double lowHeightPenalty = Math.max(0.0, -height) * 8.0;
            double confFloor = 0.65 + Math.min(0.35, Math.max(0.0, conf) * 0.35);
            return Math.max(0.0, speed * heightBonus * confFloor - lowHeightPenalty);
        }

        private static String activeSide(FramePose frame, double leftSpeed, double rightSpeed) {
            double left = activeScore(frame, "left", leftSpeed);
            double right = activeScore(frame, "right", rightSpeed);
            return left >= right ? "left" : "right";
        }

        private static double activeScore(FramePose frame, String side, double speed) {
            int shoulder = "left".equals(side) ? 11 : 12;
            int elbow = "left".equals(side) ? 13 : 14;
            int wrist = "left".equals(side) ? 15 : 16;
            Point2 sh = point(frame, shoulder);
            Point2 wr = point(frame, wrist);
            double height = sh != null && wr != null ? Math.max(0.0, sh.y - wr.y) : 0.0;
            double conf = Math.min(Math.min(visibility(frame, shoulder), visibility(frame, elbow)), visibility(frame, wrist));
            return speed * (1.0 + height / 65.0) + conf * 12.0;
        }

        private static void enrichAngularSpeeds(List<MetricFrame> records, double fps) {
            for (int i = 1; i < records.size(); i++) {
                MetricFrame prev = records.get(i - 1);
                MetricFrame cur = records.get(i);
                cur.elbowAngularSpeed = cur.elbowAngle == null || prev.elbowAngle == null ? 0.0 : Math.abs(cur.elbowAngle - prev.elbowAngle) * fps;
                cur.kneeAngularSpeed = cur.kneeAngle == null || prev.kneeAngle == null ? 0.0 : Math.abs(cur.kneeAngle - prev.kneeAngle) * fps;
            }
        }

        private static double orderScore(List<MetricFrame> legBand, List<MetricFrame> trunkArmBand, List<MetricFrame> wristBand, double fps) {
            Double legCenter = energyCenter(legBand, "kneeAngularSpeed");
            Double trunkCenter = twistCenter(trunkArmBand, fps);
            Double elbowCenter = energyCenter(trunkArmBand, "elbowAngularSpeed");
            Double wristCenter = energyCenter(wristBand, "normalizedWristSpeed");
            List<Double> pairScores = new ArrayList<>();
            addOrderPair(pairScores, legCenter, trunkCenter, fps);
            addOrderPair(pairScores, trunkCenter, elbowCenter, fps);
            addOrderPair(pairScores, elbowCenter, wristCenter, fps);
            if (pairScores.isEmpty()) return 50.0;
            double sum = 0.0;
            for (Double score : pairScores) sum += score;
            return sum / pairScores.size();
        }

        private static void addOrderPair(List<Double> scores, Double from, Double to, double fps) {
            if (from == null || to == null) return;
            scores.add(scoreRange((to - from) / Math.max(fps, 1.0), -0.08, 0.22));
        }

        private static Double energyCenter(List<MetricFrame> items, String field) {
            double total = 0.0;
            double weighted = 0.0;
            for (MetricFrame item : items) {
                double value = Math.max(0.0, value(item, field, 0.0));
                if (value <= 0.0) continue;
                total += value;
                weighted += item.frame * value;
            }
            return total <= 0.0 ? null : weighted / total;
        }

        private static Double twistCenter(List<MetricFrame> items, double fps) {
            double total = 0.0;
            double weighted = 0.0;
            for (int i = 1; i < items.size(); i++) {
                double value = Math.abs(safe(items.get(i).twist, 0.0) - safe(items.get(i - 1).twist, 0.0)) * fps;
                if (value <= 0.0) continue;
                total += value;
                weighted += items.get(i).frame * value;
            }
            return total <= 0.0 ? null : weighted / total;
        }

        private static List<MetricFrame> band(List<MetricFrame> records, int eventIdx, double fps, double startOffset, double endOffset) {
            int lo = Math.max(0, eventIdx + (int) Math.round(startOffset * fps));
            int hi = Math.min(records.size() - 1, eventIdx + (int) Math.round(endOffset * fps));
            if (hi < lo) return Collections.emptyList();
            return records.subList(lo, hi + 1);
        }

        private static int peakFrame(List<MetricFrame> items, String field, int fallback) {
            double best = -Double.MAX_VALUE;
            int frame = fallback;
            for (MetricFrame item : items) {
                double value = value(item, field, -Double.MAX_VALUE);
                if (value > best) {
                    best = value;
                    frame = item.frame;
                }
            }
            return frame;
        }

        private static double twistEnergy(List<MetricFrame> items, double fps) {
            List<Double> values = new ArrayList<>();
            for (int i = 1; i < items.size(); i++) {
                values.add(Math.abs(safe(items.get(i).twist, 0.0) - safe(items.get(i - 1).twist, 0.0)) * fps);
            }
            return percentile(values, 80, 0.0);
        }

        private static double percentileD(List<MetricFrame> items, String field, double pct, double fallback) {
            List<Double> values = new ArrayList<>();
            for (MetricFrame item : items) {
                values.add(value(item, field, fallback));
            }
            return percentile(values, pct, fallback);
        }

        private static double percentile(List<Double> values, double pct, double fallback) {
            List<Double> clean = new ArrayList<>();
            for (Double value : values) {
                if (value != null && Double.isFinite(value)) clean.add(value);
            }
            if (clean.isEmpty()) return fallback;
            clean.sort(Comparator.naturalOrder());
            int idx = (int) Math.round((pct / 100.0) * (clean.size() - 1));
            return clean.get(Math.min(clean.size() - 1, Math.max(0, idx)));
        }

        private static double medianD(List<MetricFrame> items, String field, double fallback) {
            List<Double> values = new ArrayList<>();
            for (MetricFrame item : items) {
                double value = value(item, field, Double.NaN);
                if (Double.isFinite(value)) values.add(value);
            }
            if (values.isEmpty()) return fallback;
            values.sort(Comparator.naturalOrder());
            return values.get(values.size() / 2);
        }

        private static double maxD(List<MetricFrame> items, String field, double fallback) {
            double max = -Double.MAX_VALUE;
            for (MetricFrame item : items) {
                double value = value(item, field, Double.NaN);
                if (Double.isFinite(value)) max = Math.max(max, value);
            }
            return max == -Double.MAX_VALUE ? fallback : max;
        }

        private static double value(MetricFrame item, String field, double fallback) {
            switch (field) {
                case "torso": return item.torso == null ? fallback : item.torso;
                case "armConfidence": return item.armConfidence;
                case "wristAboveShoulder": return item.wristAboveShoulder == null ? fallback : item.wristAboveShoulder;
                case "twist": return item.twist == null ? fallback : item.twist;
                case "kneeAngularSpeed": return item.kneeAngularSpeed;
                case "elbowAngularSpeed": return item.elbowAngularSpeed;
                case "normalizedWristSpeed": return item.normalizedWristSpeed;
                default: return fallback;
            }
        }

        private static List<Point2> smooth(List<Point2> input, double alpha) {
            List<Point2> output = new ArrayList<>();
            Point2 prev = null;
            for (Point2 item : input) {
                if (item == null) {
                    output.add(prev);
                    continue;
                }
                if (prev == null) {
                    prev = item;
                } else {
                    prev = new Point2(prev.x * (1.0 - alpha) + item.x * alpha, prev.y * (1.0 - alpha) + item.y * alpha, item.visibility);
                }
                output.add(prev);
            }
            return output;
        }

        private static double[] speed(List<Point2> points, double fps) {
            double[] out = new double[points.size()];
            for (int i = 1; i < points.size(); i++) {
                Point2 prev = points.get(i - 1);
                Point2 cur = points.get(i);
                out[i] = prev == null || cur == null ? 0.0 : distance(prev, cur) * fps;
            }
            return out;
        }

        private static Point2 point(FramePose frame, int idx) {
            return frame.pose == null || idx < 0 || idx >= frame.pose.length ? null : frame.pose[idx];
        }

        private static double visibility(FramePose frame, int idx) {
            Point2 point = point(frame, idx);
            return point == null ? 0.0 : point.visibility;
        }

        private static Double angle(Point2 a, Point2 b, Point2 c) {
            if (a == null || b == null || c == null) return null;
            double v1x = a.x - b.x;
            double v1y = a.y - b.y;
            double v2x = c.x - b.x;
            double v2y = c.y - b.y;
            double denom = Math.hypot(v1x, v1y) * Math.hypot(v2x, v2y);
            if (denom <= 1e-6) return null;
            double cos = Math.max(-1.0, Math.min(1.0, (v1x * v2x + v1y * v2y) / denom));
            return Math.toDegrees(Math.acos(cos));
        }

        private static Double twist(FramePose frame) {
            Double shoulder = lineAngle(point(frame, 11), point(frame, 12));
            Double hip = lineAngle(point(frame, 23), point(frame, 24));
            if (shoulder == null || hip == null) return null;
            double delta = (shoulder - hip + 180.0) % 360.0 - 180.0;
            return Math.abs(delta);
        }

        private static Double lineAngle(Point2 a, Point2 b) {
            if (a == null || b == null) return null;
            return Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
        }

        private static Double torso(FramePose frame) {
            Point2 lsh = point(frame, 11);
            Point2 rsh = point(frame, 12);
            Point2 lhp = point(frame, 23);
            Point2 rhp = point(frame, 24);
            if (lsh == null || rsh == null || lhp == null || rhp == null) return null;
            Point2 shoulder = new Point2((lsh.x + rsh.x) * 0.5, (lsh.y + rsh.y) * 0.5, 1.0);
            Point2 hip = new Point2((lhp.x + rhp.x) * 0.5, (lhp.y + rhp.y) * 0.5, 1.0);
            return distance(shoulder, hip);
        }

        private static double distance(Point2 a, Point2 b) {
            return Math.hypot(a.x - b.x, a.y - b.y);
        }

        private static double scoreRange(double value, double low, double high) {
            if (Math.abs(high - low) <= 1e-6) return 50.0;
            return clamp((value - low) / (high - low) * 100.0);
        }

        private static double scoreBand(Double value, double ideal, double tolerance) {
            if (value == null) return 50.0;
            return clamp(100.0 - Math.abs(value - ideal) / Math.max(tolerance, 1e-6) * 55.0);
        }

        private static double safe(Double value, double fallback) {
            return value == null || !Double.isFinite(value) ? fallback : value;
        }

        private static double clamp(double value) {
            return Math.max(0.0, Math.min(100.0, value));
        }
    }
}
