const videoInput = document.getElementById("videoInput");
const fileName = document.getElementById("fileName");
const startButton = document.getElementById("startButton");
const dropZone = document.getElementById("dropZone");
const uploadPanel = document.getElementById("uploadPanel");
const jobPanel = document.getElementById("jobPanel");
const resultPanel = document.getElementById("resultPanel");
const errorPanel = document.getElementById("errorPanel");
const stageText = document.getElementById("stageText");
const jobText = document.getElementById("jobText");
const progressBar = document.getElementById("progressBar");
const progressText = document.getElementById("progressText");
const openReport = document.getElementById("openReport");
const openOverlay = document.getElementById("openOverlay");
const openPoseOverlay = document.getElementById("openPoseOverlay");
const openJson = document.getElementById("openJson");
const openLog = document.getElementById("openLog");
const openResultLog = document.getElementById("openResultLog");
const liveLog = document.getElementById("liveLog");
const errorText = document.getElementById("errorText");
const reportFrame = document.getElementById("reportFrame");
const overlayVideo = document.getElementById("overlayVideo");
const overlayPreviewCard = overlayVideo.closest(".side-card");
const newReviewButton = document.getElementById("newReviewButton");
const retryButton = document.getElementById("retryButton");
const recentPanel = document.getElementById("recentPanel");
const recentJobs = document.getElementById("recentJobs");
const refreshJobsButton = document.getElementById("refreshJobsButton");
const historyMeta = document.getElementById("historyMeta");
const workspaceTitle = document.getElementById("workspaceTitle");
const modeHint = document.getElementById("modeHint");
const reviewModeInputs = Array.from(document.querySelectorAll('input[name="reviewMode"]'));

const MAX_UPLOAD_BYTES = 6 * 1024 * 1024 * 1024;
const ACCEPTED_EXTENSIONS = [".mp4", ".mov", ".m4v", ".avi", ".mkv", ".webm"];
const MAX_HISTORY_ITEMS = 20;

let selectedFile = null;
let pollTimer = null;
let currentResult = null;

function selectedMode() {
  return reviewModeInputs.find((input) => input.checked)?.value || "short";
}

function modeLabel(mode) {
  return mode === "long" ? "长视频骨架" : "短视频复盘";
}

function updateModeHint() {
  const mode = selectedMode();
  modeHint.textContent = mode === "long"
    ? "长视频模式只生成全程骨架标注视频，不做重发力评分和证据分析。"
    : "短视频复盘限制 60 秒以内，会生成评分、时间轴、重发力慢放和片段截取。";
}

function setFile(file) {
  selectedFile = file || null;
  fileName.textContent = file ? file.name : "选择或拖入视频";
  startButton.disabled = !file;
}

function showOnly(panel) {
  uploadPanel.hidden = panel !== uploadPanel;
  jobPanel.hidden = panel !== jobPanel;
  resultPanel.hidden = panel !== resultPanel;
  errorPanel.hidden = panel !== errorPanel;
  newReviewButton.hidden = panel === uploadPanel;
}

function resetFlow() {
  clearInterval(pollTimer);
  videoInput.value = "";
  setFile(null);
  currentResult = null;
  resultPanel.classList.remove("long-mode");
  overlayVideo.removeAttribute("src");
  reportFrame.removeAttribute("src");
  showOnly(uploadPanel);
  loadRecentJobs();
}

function validateFile(file) {
  if (!file) return "请选择视频文件。";
  const name = file.name.toLowerCase();
  const accepted = ACCEPTED_EXTENSIONS.some((suffix) => name.endsWith(suffix));
  if (!accepted) return "当前只支持 MP4、MOV、M4V、AVI、MKV、WEBM。";
  if (file.size > MAX_UPLOAD_BYTES) return "视频超过 6GB，请先压缩或裁剪视频。";
  return "";
}

videoInput.addEventListener("change", () => {
  setFile(videoInput.files && videoInput.files[0]);
});

for (const eventName of ["dragenter", "dragover"]) {
  dropZone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropZone.classList.add("dragging");
  });
}

for (const eventName of ["dragleave", "drop"]) {
  dropZone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropZone.classList.remove("dragging");
  });
}

dropZone.addEventListener("drop", (event) => {
  const file = event.dataTransfer.files && event.dataTransfer.files[0];
  if (file) setFile(file);
});

startButton.addEventListener("click", () => {
  const validationError = validateFile(selectedFile);
  if (validationError) {
    showError({ error: validationError, result: null });
    return;
  }
  uploadVideo(selectedFile);
});

newReviewButton.addEventListener("click", resetFlow);
retryButton.addEventListener("click", resetFlow);
refreshJobsButton.addEventListener("click", loadRecentJobs);
for (const input of reviewModeInputs) {
  input.addEventListener("change", updateModeHint);
}

function uploadVideo(file) {
  const mode = selectedMode();
  clearInterval(pollTimer);
  showOnly(jobPanel);
  updateProgress({ status: "uploading", stage: "上传视频", progress: 1, mode });

  const form = new FormData();
  form.append("video", file);
  form.append("mode", mode);
  const request = new XMLHttpRequest();
  request.open("POST", "/api/jobs");
  request.upload.addEventListener("progress", (event) => {
    if (!event.lengthComputable) return;
    const uploadProgress = Math.min(10, Math.max(1, (event.loaded / event.total) * 10));
    updateProgress({ status: "uploading", stage: "上传视频", progress: uploadProgress, mode });
  });
  request.addEventListener("load", () => {
    if (request.status < 200 || request.status >= 300) {
      showError({ error: request.responseText || "上传失败", result: null });
      return;
    }
    const job = JSON.parse(request.responseText);
    updateProgress(job);
    pollJob(job.id);
  });
  request.addEventListener("error", () => {
    showError({ error: "网络连接失败，任务没有创建。", result: null });
  });
  request.send(form);
}

function pollJob(jobId) {
  clearInterval(pollTimer);
  pollTimer = setInterval(async () => {
    try {
      const response = await fetch(`/api/jobs/${jobId}`);
      if (!response.ok) throw new Error(await response.text());
      const job = await response.json();
      updateProgress(job);
      if (job.status === "completed") {
        clearInterval(pollTimer);
        showResult(job);
      } else if (job.status === "failed") {
        clearInterval(pollTimer);
        showError(job);
      }
    } catch (error) {
      clearInterval(pollTimer);
      showError({ id: jobId, error: String(error), result: { log_url: `/api/jobs/${jobId}/log` } });
    }
  }, 1500);
}

function updateProgress(job) {
  const progress = Math.max(0, Math.min(100, Number(job.progress || 0)));
  stageText.textContent = job.stage || "排队中";
  jobText.textContent = statusText(job.status);
  progressBar.style.width = `${progress}%`;
  progressText.textContent = `${Math.round(progress)}%`;
  if (job.id) {
    liveLog.hidden = false;
    liveLog.href = `/api/jobs/${job.id}/log`;
  } else {
    liveLog.hidden = true;
  }
}

function statusText(status) {
  if (status === "uploading") return "正在把视频传到本机分析队列。";
  if (status === "queued") return "任务已经进入队列。";
  if (status === "processing") return "正在预处理视频、提取骨架并生成复盘。长视频会需要几分钟。";
  if (status === "completed") return "复盘已经生成。";
  if (status === "failed") return "处理失败，请查看日志。";
  return "正在准备任务。";
}

function setHref(element, url) {
  if (!url) {
    element.removeAttribute("href");
    element.setAttribute("aria-disabled", "true");
    return;
  }
  element.href = url;
  element.removeAttribute("aria-disabled");
}

function showResult(job) {
  showOnly(resultPanel);
  currentResult = job.result || {};
  const mode = job.mode || "short";
  const longMode = mode === "long";
  setHref(openReport, currentResult.report_url);
  setHref(openOverlay, currentResult.overlay_url);
  setHref(openPoseOverlay, currentResult.pose_overlay_url);
  setHref(openJson, currentResult.json_url);
  setHref(openResultLog, currentResult.log_url);
  if (currentResult.overlay_url) {
    overlayVideo.src = currentResult.overlay_url;
  }
  reportFrame.src = currentResult.report_url || "about:blank";
  workspaceTitle.textContent = longMode ? "长视频骨架渲染" : "动作复盘";
  openReport.textContent = longMode ? "骨架页面" : "完整页面";
  openOverlay.textContent = longMode ? "下载骨架标注视频" : "下载标注视频";
  resultPanel.classList.toggle("long-mode", longMode);
  const resultSide = resultPanel.querySelector(".result-side");
  if (resultSide) resultSide.hidden = longMode;
  if (overlayPreviewCard) overlayPreviewCard.hidden = longMode;
  openJson.hidden = longMode;
  openPoseOverlay.hidden = longMode;
  loadRecentJobs();
}

function showError(job) {
  showOnly(errorPanel);
  errorText.textContent = normalizeError(job.error || "未知错误");
  const logUrl = job.result && job.result.log_url ? job.result.log_url : job.id ? `/api/jobs/${job.id}/log` : "";
  setHref(openLog, logUrl);
}

function normalizeError(message) {
  try {
    const parsed = JSON.parse(message);
    if (parsed.detail) return parsed.detail;
  } catch {
    return message;
  }
  return message;
}

async function loadRecentJobs() {
  try {
    const response = await fetch(`/api/jobs?limit=${MAX_HISTORY_ITEMS}`);
    if (!response.ok) throw new Error(await response.text());
    const data = await response.json();
    renderRecentJobs(data.jobs || []);
  } catch {
    recentPanel.hidden = true;
  }
}

function renderRecentJobs(jobs) {
  const completed = jobs.filter((job) => job.status === "completed" && job.result && job.result.report_url);
  recentPanel.hidden = false;
  historyMeta.textContent = completed.length
    ? `已保留 ${completed.length}/${MAX_HISTORY_ITEMS} 条本机复盘，点击任意记录继续查看。`
    : `最多保留 ${MAX_HISTORY_ITEMS} 条本机复盘；完成上传后会出现在这里。`;
  recentJobs.innerHTML = "";
  if (completed.length === 0) {
    const empty = document.createElement("div");
    empty.className = "recent-empty";
    empty.textContent = "暂无历史复盘。";
    recentJobs.appendChild(empty);
    return;
  }
  for (const job of completed) {
    const item = document.createElement("button");
    item.type = "button";
    item.className = "recent-item";
    item.innerHTML = `
      <span>${escapeHtml(job.filename || "未命名视频")}</span>
      <small>${modeLabel(job.mode)} · ${formatTime(job.completed_at || job.updated_at)} · ${formatDuration(job)}</small>
    `;
    item.addEventListener("click", () => showResult(job));
    recentJobs.appendChild(item);
  }
}

function formatDuration(job) {
  const bytes = Number(job.size_bytes || 0);
  if (!Number.isFinite(bytes) || bytes <= 0) return "已完成";
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
  return `${Math.max(1, Math.round(bytes / 1024))}KB`;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function formatTime(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

loadRecentJobs();
updateModeHint();
