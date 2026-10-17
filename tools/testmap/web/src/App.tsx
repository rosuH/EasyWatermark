import { useEffect, useRef, useState } from "react";
import {
  ArrowRight,
  ArrowUp,
  ArrowDown,
  GitBranch,
  History,
  ListChecks,
  Play,
  RefreshCw,
  Settings2,
  Terminal,
  X,
} from "lucide-react";
import { ApiError, api, post, useResource } from "./api";
import type {
  DevicesResponse,
  ExecutionMode,
  RoutePlan,
  MapResponse,
  RunsResponse,
  RunStarted,
  StatusResponse,
  TasksResponse,
} from "./api-types";
import { type Catalog, type Lang, duration, humanError, title } from "./model";
import { CatalogView } from "./Catalog";
import { RunView } from "./Run";
import { Button } from "./components/ui/button";
import { CopyButton, Empty, ErrorNotice, Language, Status, useT } from "./ui";

const ANDROID_JOURNEY_SCENARIOS = [
  {
    id: "android-ai-journey",
    taskLabel: "Android AI journey: pick, edit and export",
    titleEn: "Android AI journey",
    titleZh: "Android AI 完整流程",
    optionEn: "Pick, edit and export",
    optionZh: "选择、编辑并导出",
    descriptionEn:
      "Pick a photo → edit watermark text → change typeface and style → export.",
    descriptionZh: "选择照片 → 修改水印文字 → 修改字体与样式 → 导出。",
    runEn: "Run Android AI journey",
    runZh: "运行 Android AI 完整流程",
  },
  {
    id: "android-large-image-preview",
    taskLabel: "Android large image preview: diagnose Canvas crash",
    titleEn: "Android large image preview",
    titleZh: "Android 大图预览",
    optionEn: "Large-image preview crash",
    optionZh: "大图预览崩溃复现",
    descriptionEn:
      "Open an isolated 49,861 × 1,350 photo in the editor and capture whether the Canvas preview crash reproduces.",
    descriptionZh:
      "在编辑器中打开隔离的 49,861 × 1,350 图片，并记录 Canvas 预览崩溃是否复现。",
    runEn: "Run large-image crash reproduction",
    runZh: "运行大图崩溃复现",
  },
] as const;

type AndroidJourneyScenario = (typeof ANDROID_JOURNEY_SCENARIOS)[number];
type AndroidJourneyScenarioId = AndroidJourneyScenario["id"];

function findJourneyTask(
  tasks: TasksResponse | undefined,
  scenario: AndroidJourneyScenario,
) {
  return tasks?.runnable.find(
    (candidate) =>
      candidate.id === scenario.id &&
      candidate.label === scenario.taskLabel &&
      candidate.builder === "e2e-runner" &&
      candidate.needs_device === "android" &&
      candidate.platforms.includes("android"),
  );
}

function Workspace() {
  const lang = ReactLanguage();
  const t = useT();
  const [view, setView] = useState<"catalog" | "run" | "history">("catalog"),
    [revision, setRevision] = useState(0),
    [runId, setRunId] = useState<string | null>(null);
  const catalog = useResource<Catalog>("/api/catalog", 0, revision),
    map = useResource<MapResponse>(
      "/api/map",
      view === "catalog" ? 5000 : 0,
      revision,
    ),
    tasks = useResource<TasksResponse>("/api/tasks", 0, revision),
    devices = useResource<DevicesResponse>("/api/devices", 0, revision),
    runs = useResource<RunsResponse>(
      view === "history" ? "/api/runs" : null,
      5000,
      revision,
    ),
    status = useResource<StatusResponse>(
      view === "catalog" ? "/api/status" : null,
      view === "catalog" ? 2000 : 0,
      revision,
    );
  const [draft, setDraft] = useState<string[]>([]),
    [settings, setSettings] = useState(false),
    [executionMode, setExecutionMode] = useState<ExecutionMode>("independent"),
    [routePreview, setRoutePreview] = useState<{
      key: string;
      plan?: RoutePlan;
      error?: string;
    }>(),
    [device, setDevice] = useState("auto"),
    [repeat, setRepeat] = useState(1),
    [starting, setStarting] = useState(false),
    [error, setError] = useState(""),
    [journeyDevice, setJourneyDevice] = useState(""),
    [journeyScenarioId, setJourneyScenarioId] =
      useState<AndroidJourneyScenarioId>("android-ai-journey"),
    [journeyStarting, setJourneyStarting] = useState(false),
    [journeyError, setJourneyError] = useState("");
  const routeKey = JSON.stringify([draft, device]);
  const routePlan =
    routePreview?.key === routeKey ? routePreview.plan : undefined;
  const routeError =
    routePreview?.key === routeKey ? routePreview.error : undefined;
  const allDevices = Object.values(devices.data || {}).flat();
  const androidDevices = devices.data?.android || [];
  const journeyScenario = ANDROID_JOURNEY_SCENARIOS.find(
    (scenario) => scenario.id === journeyScenarioId,
  )!;
  const journeyTask = findJourneyTask(tasks.data, journeyScenario);
  const journeyScenarioAvailable = (scenario: AndroidJourneyScenario) =>
    !!findJourneyTask(tasks.data, scenario);
  const boundJourneyDevice = androidDevices.find(
    (candidate) => candidate.id === journeyDevice,
  );
  const journeyBusy = status.data?.active === true;
  const canStartJourney =
    !!journeyTask &&
    !tasks.error &&
    !devices.error &&
    !journeyBusy &&
    !journeyStarting &&
    boundJourneyDevice?.state === "ready";
  const routeDeviceMismatch =
    executionMode === "route" &&
    routePlan?.platform &&
    allDevices.some(
      (candidate) =>
        candidate.id === device && candidate.platform !== routePlan.platform,
    );
  const canStart =
    !!draft.length &&
    !starting &&
    (executionMode === "independent" ||
      (routePlan?.supported === true && !routeDeviceMismatch));
  useEffect(() => {
    if (executionMode !== "route") return;
    const controller = new AbortController();
    setRoutePreview(undefined);
    void api<RoutePlan>("/api/route-plan", {
      method: "POST",
      body: JSON.stringify({ tasks: draft, device }),
      signal: controller.signal,
    }).then(
      (plan) => {
        if (!controller.signal.aborted)
          setRoutePreview({ key: routeKey, plan });
      },
      (error) => {
        if (!controller.signal.aborted)
          setRoutePreview({ key: routeKey, error: humanError(error, lang) });
      },
    );
    return () => controller.abort();
  }, [executionMode, routeKey, lang]);
  const move = (index: number, direction: number) =>
    setDraft((old) => {
      const next = [...old];
      [next[index], next[index + direction]] = [
        next[index + direction],
        next[index],
      ];
      return next;
    });
  const refresh = () => setRevision((r) => r + 1);
  const toggle = (id: string) =>
    setDraft((old) =>
      old.includes(id) ? old.filter((t) => t !== id) : [...old, id],
    );
  const openRun = (id: string) => {
    setRunId(id);
    setView("run");
  };
  const allPlatforms = [
    ...new Set(
      catalog.data?.edges.flatMap((e) => Object.keys(e.platforms)) || [],
    ),
  ];
  const mobilePlatforms = allPlatforms.filter(
    (p) =>
      allDevices.some((d) => d.platform === p && d.kind !== "host") ||
      Object.values(catalog.data?.agent || {}).some((a) => a[p] === true),
  );
  const start = async () => {
    setStarting(true);
    setError("");
    try {
      await post<RunStarted>("/api/run", {
        tasks: draft,
        device,
        repeat,
        source: "manual",
        execution_mode: executionMode,
      });
      setRunId(null);
      setView("run");
      setSettings(false);
      refresh();
    } catch (e) {
      setError(humanError(e, lang));
    } finally {
      setStarting(false);
    }
  };
  const startJourney = async () => {
    if (!canStartJourney || !journeyTask || !boundJourneyDevice) return;
    setJourneyStarting(true);
    setJourneyError("");
    try {
      await post<RunStarted>("/api/run", {
        tasks: [journeyTask.id],
        device: boundJourneyDevice.id,
        repeat: 1,
        execution_mode: "independent",
      });
      setRunId(null);
      setView("run");
      refresh();
    } catch (e) {
      setJourneyError(
        e instanceof ApiError && e.status === 409
          ? `${t("Another run is active. Wait for it to finish or stop it, then retry.", "已有任务正在运行。请等待其完成或先停止，再重试。")} ${e.message}`
          : humanError(e, lang),
      );
    } finally {
      setJourneyStarting(false);
    }
  };
  const label = (id: string) => {
    if (id.startsWith("edge:") && catalog.data) {
      const [e, rest] = id.slice(5).split("@");
      return `${title(catalog.data, "edges", e, lang)} · ${rest || ""}`;
    }
    return tasks.data?.runnable.find((t) => t.id === id)?.label || id;
  };
  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if (
        event.key === "/" &&
        !(event.target instanceof HTMLInputElement) &&
        !(event.target instanceof HTMLTextAreaElement)
      ) {
        const input = document.querySelector<HTMLInputElement>(".search input");
        if (input) {
          event.preventDefault();
          input.focus();
        }
      }
      if (event.key === "Escape") setSettings(false);
    };
    document.addEventListener("keydown", shortcut);
    return () => document.removeEventListener("keydown", shortcut);
  }, []);
  return (
    <>
      <header className="app-header">
        <div className="brand">
          <div className="brand-mark">
            <GitBranch size={20} />
          </div>
          <span>
            testmap
            <small>{t("Evidence, then confidence.", "以证据建立信心。")}</small>
          </span>
        </div>
        <nav aria-label={t("Main navigation", "主导航")}>
          <Button
            variant="ghost"
            aria-current={view === "catalog" ? "page" : undefined}
            onClick={() => setView("catalog")}
          >
            <GitBranch size={16} />
            {t("Catalog", "目录")}
          </Button>
          <Button
            variant="ghost"
            aria-current={view === "run" ? "page" : undefined}
            onClick={() => {
              setView("run");
            }}
          >
            <Play size={15} />
            {t("Execution", "执行")}
          </Button>
          <Button
            variant="ghost"
            aria-current={view === "history" ? "page" : undefined}
            onClick={() => setView("history")}
          >
            <History size={16} />
            {t("History", "历史")}
          </Button>
        </nav>
        <div className="header-meta">
          <code>
            {map.data?.head?.sha.slice(0, 8) || "—"}
            {map.data?.head?.dirty ? " + dirty" : ""}
          </code>
          <span className={`connection ${map.error ? "offline" : ""}`}>
            <i />
            {map.error
              ? t("Offline", "离线")
              : map.data
                ? t("Connected", "已连接")
                : t("Connecting", "连接中")}
          </span>
          <LanguageToggle />
        </div>
      </header>
      <main>
        {(catalog.error || map.error) && (
          <ErrorNotice>
            {t("Console connection unavailable.", "控制台连接不可用。")}{" "}
            {catalog.error || map.error}
            <Button size="sm" onClick={refresh}>
              <RefreshCw size={13} />
              {t("Retry", "重试")}
            </Button>
          </ErrorNotice>
        )}
        {!catalog.data || !map.data ? (
          <div className="panel">
            <Empty
              title={
                catalog.error || map.error
                  ? t("Unable to load the catalog", "无法载入目录")
                  : t("Loading the catalog…", "正在载入目录…")
              }
              detail={t(
                "The console serves the catalog and its evidence.",
                "目录与证据由本地控制台提供。",
              )}
            />
          </div>
        ) : view === "catalog" ? (
          <>
            <section
              className="android-journey panel"
              aria-labelledby="android-journey-title"
            >
              <div className="android-journey-copy">
                <span className="eyebrow">
                  {t("ANDROID · REAL UI FLOW", "Android · 真实界面流程")}
                </span>
                <h2 id="android-journey-title">
                  {t(journeyScenario.titleEn, journeyScenario.titleZh)}
                </h2>
                <p>
                  {t(
                    journeyScenario.descriptionEn,
                    journeyScenario.descriptionZh,
                  )}
                </p>
                <small>
                  {t(
                    "Uses an isolated test photo and attempts to restore device state after the run.",
                    "使用隔离测试图片，并在运行结束后尝试还原设备状态。",
                  )}
                </small>
              </div>
              <div className="android-journey-controls">
                <label htmlFor="android-journey-scenario">
                  {t("Android scenario", "Android 场景")}
                </label>
                <select
                  id="android-journey-scenario"
                  value={journeyScenarioId}
                  onChange={(event) => {
                    setJourneyScenarioId(
                      event.target.value as AndroidJourneyScenarioId,
                    );
                    setJourneyError("");
                  }}
                  disabled={journeyBusy || journeyStarting}
                >
                  {ANDROID_JOURNEY_SCENARIOS.map((scenario) => {
                    const available = journeyScenarioAvailable(scenario);
                    return (
                      <option
                        key={scenario.id}
                        value={scenario.id}
                        disabled={!available}
                      >
                        {t(scenario.optionEn, scenario.optionZh)}
                        {!available
                          ? t(" · unavailable", " · 不可用")
                          : ""}
                      </option>
                    );
                  })}
                </select>
                <label htmlFor="android-journey-device">
                  {t("Android device", "Android 设备")}
                </label>
                <select
                  id="android-journey-device"
                  value={journeyDevice}
                  onChange={(event) => {
                    setJourneyDevice(event.target.value);
                    setJourneyError("");
                  }}
                  disabled={!androidDevices.length}
                >
                  <option value="">
                    {androidDevices.length
                      ? t("Choose a device…", "选择设备…")
                      : t(
                          "No Android device available",
                          "没有可用的 Android 设备",
                        )}
                  </option>
                  {androidDevices.map((candidate) => (
                    <option
                      key={candidate.id}
                      value={candidate.id}
                      disabled={candidate.state !== "ready"}
                    >
                      {candidate.name} · {candidate.state}
                    </option>
                  ))}
                </select>
                <small className="android-journey-binding">
                  {boundJourneyDevice
                    ? `${t("Bound to", "绑定设备")}: ${boundJourneyDevice.name} (${boundJourneyDevice.id}) · ${boundJourneyDevice.state}`
                    : t(
                        "Select one ready Android device.",
                        "请选择一台就绪的 Android 设备。",
                      )}
                </small>
                {journeyBusy && (
                  <p className="danger-text android-journey-state">
                    {t("A run is already active", "当前已有任务运行")}
                    {status.data?.id ? ` · ${status.data.id}` : ""}
                    {t(
                      ". Wait or stop it in Execution.",
                      "。请等待完成或在“执行”中停止。",
                    )}
                  </p>
                )}
                <div className="row">
                  <Button
                    variant="default"
                    disabled={!canStartJourney}
                    onClick={() => void startJourney()}
                  >
                    <Play size={14} />
                    {journeyStarting
                      ? t("Starting…", "正在启动…")
                      : t(journeyScenario.runEn, journeyScenario.runZh)}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={refresh}>
                    <RefreshCw size={13} />
                    {t("Refresh devices", "刷新设备")}
                  </Button>
                </div>
              </div>
              {(tasks.error ||
                devices.error ||
                status.error ||
                journeyError ||
                (tasks.data && !journeyTask) ||
                (devices.data && !androidDevices.length) ||
                (boundJourneyDevice &&
                  boundJourneyDevice.state !== "ready")) && (
                <div className="android-journey-feedback">
                  {tasks.error && <ErrorNotice>{tasks.error}</ErrorNotice>}
                  {devices.error && <ErrorNotice>{devices.error}</ErrorNotice>}
                  {status.error && (
                    <small className="danger-text">
                      {t(
                        "Could not check whether another run is active. The server will check again when you start.",
                        "无法检查是否有任务正在运行。启动时服务器会再次检查。",
                      )}{" "}
                      {status.error}
                    </small>
                  )}
                  {journeyError && <ErrorNotice>{journeyError}</ErrorNotice>}
                  {tasks.data && !journeyTask && (
                    <small className="danger-text">
                      {t(
                        "This runner does not expose the selected Android scenario.",
                        "此 runner 未提供所选 Android 场景。",
                      )}
                    </small>
                  )}
                  {devices.data && !androidDevices.length && (
                    <small className="danger-text">
                      {t(
                        "Connect an Android device, then refresh.",
                        "连接 Android 设备后刷新。",
                      )}
                    </small>
                  )}
                  {boundJourneyDevice &&
                    boundJourneyDevice.state !== "ready" && (
                      <small className="danger-text">
                        {t(
                          "Selected device is not ready. Choose a ready device or refresh.",
                          "所选设备尚未就绪。请选择就绪设备或刷新。",
                        )}
                      </small>
                    )}
                </div>
              )}
            </section>
            <CatalogView
              catalog={catalog.data}
              map={map.data}
              draft={draft}
              routeMode={executionMode === "route"}
              toggle={toggle}
              refresh={refresh}
              openRun={openRun}
            />
          </>
        ) : view === "run" ? (
          <RunView
            key={runId || "current"}
            runId={runId}
            platforms={mobilePlatforms}
            catalog={catalog.data}
            onCurrent={() => setRunId(null)}
          />
        ) : (
          <section className="panel history">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">
                  {t("TRACEABLE RESULTS", "可追溯结果")}
                </span>
                <h1>{t("Run history", "运行历史")}</h1>
              </div>
              <span className="tag">
                {runs.data?.runs.length || 0} {t("runs", "次运行")}
              </span>
            </div>
            {runs.error && <ErrorNotice>{runs.error}</ErrorNotice>}
            {runs.data?.runs.length ? (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      {[
                        t("Result / run", "结果 / 运行"),
                        t("Started", "开始时间"),
                        t("Revision", "版本"),
                        t("Source / target", "来源 / 目标"),
                        t("Checks", "检查"),
                        t("Duration", "耗时"),
                      ].map((h) => (
                        <th key={h}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {runs.data.runs.map((run) => (
                      <tr key={run.id}>
                        <td>
                          <button
                            className="history-open"
                            onClick={() => openRun(run.id)}
                          >
                            <Status state={run.state} />
                            <code>{run.id}</code>
                            <ArrowRight size={14} />
                          </button>
                        </td>
                        <td>
                          {new Date(run.started).toLocaleString(
                            lang === "zh" ? "zh-CN" : "en-GB",
                          )}
                        </td>
                        <td>
                          <code>
                            {run.git?.sha?.slice(0, 8)}
                            {run.git?.dirty ? " + dirty" : ""}
                          </code>
                        </td>
                        <td>
                          {run.source || "—"}
                          <small>
                            {run.device || run.platforms?.join(" + ")}
                          </small>
                        </td>
                        <td>
                          <span className="result-number">
                            {run.pass_count || 0} ✓
                          </span>{" "}
                          / {run.fail_count || 0} × / {run.uncovered_count || 0}{" "}
                          —
                        </td>
                        <td>{duration(run.duration_s)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty
                title={t("No runs yet", "尚无运行")}
                detail={t(
                  "Completed and interrupted runs will be listed here.",
                  "已完成或中断的运行都会保留在这里。",
                )}
              />
            )}
          </section>
        )}
      </main>
      <section className="draft-bar" aria-label={t("Run draft", "运行草稿")}>
        <div className="draft-summary">
          <ListChecks size={19} />
          <strong>
            {draft.length} {t("selected tasks", "个已选任务")}
          </strong>
          <span>
            {executionMode === "route"
              ? t("Continuous route", "连续路线")
              : t("Independent cases", "独立用例")}{" "}
            · {repeat}× ·{" "}
            {device === "auto"
              ? t("Automatic devices", "自动选择设备")
              : device}
          </span>
        </div>
        <div className="row">
          <Button
            onClick={() => setSettings(!settings)}
            aria-expanded={settings}
          >
            <Settings2 size={15} />
            {t("Run settings & host checks", "运行设置与主机检查")}
          </Button>
          <Button
            variant="default"
            disabled={!canStart}
            onClick={() => void start()}
          >
            <Play size={14} />
            {starting
              ? t("Starting…", "正在启动…")
              : t("Run draft", "运行草稿")}
          </Button>
        </div>
      </section>
      {error && (
        <div className="floating-error">
          <ErrorNotice>
            {error}
            <Button
              variant="ghost"
              size="icon"
              aria-label={t("Dismiss", "关闭")}
              onClick={() => setError("")}
            >
              <X size={14} />
            </Button>
          </ErrorNotice>
        </div>
      )}
      {settings && (
        <SettingsModal close={() => setSettings(false)}>
          <section
            className="settings-panel panel"
            aria-label={t("Run settings", "运行设置")}
          >
            <div className="panel-heading">
              <h2>{t("Prepare a run", "准备运行")}</h2>
              <Button
                size="icon"
                autoFocus
                aria-label={t("Close settings", "关闭设置")}
                onClick={() => setSettings(false)}
              >
                <X size={16} />
              </Button>
            </div>
            <div className="settings-content">
              <label className="execution-mode">
                {t("Execution mode", "执行方式")}
                <select
                  value={executionMode}
                  onChange={(e) =>
                    setExecutionMode(e.target.value as ExecutionMode)
                  }
                >
                  <option value="independent">
                    {t("Independent cases", "独立用例")}
                  </option>
                  <option value="route">
                    {t("Continuous route", "连续路线")}
                  </option>
                </select>
              </label>
              <div className="settings-controls">
                <label>
                  {t("Device", "设备")}
                  <select
                    value={device}
                    onChange={(e) => setDevice(e.target.value)}
                  >
                    <option value="auto">
                      {t(
                        executionMode === "route"
                          ? "Auto — one device for the route"
                          : "Auto — available platforms in parallel",
                        executionMode === "route"
                          ? "自动：整条路线使用同一设备"
                          : "自动：可用平台并行",
                      )}
                    </option>
                    {allDevices
                      .filter((d) => d.kind !== "host")
                      .map((d) => (
                        <option key={d.platform + d.id} value={d.id}>
                          {d.platform} · {d.name} · {d.state}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  {executionMode === "route"
                    ? t("Route repeats", "路线重复次数")
                    : t("Repeat", "重复")}
                  <input
                    type="number"
                    min={1}
                    max={20}
                    value={repeat}
                    onChange={(e) =>
                      setRepeat(
                        Math.max(1, Math.min(20, Number(e.target.value) || 1)),
                      )
                    }
                  />
                </label>
                <Button onClick={refresh}>
                  <RefreshCw size={14} />
                  {t("Refresh devices", "刷新设备")}
                </Button>
              </div>
              {devices.error && <ErrorNotice>{devices.error}</ErrorNotice>}
              <p className="notice">
                {executionMode === "route"
                  ? t(
                      "First version: seven audited Android paths. Try text editing → typeface/style; typeface/style must be last. Prepare once on one device, then restore once. Checks stay independent.",
                      "首版支持七条已审计的 Android 路径。可选“编辑文字 → 字体与样式”；字体与样式必须放在最后。同一设备准备一次，结束后统一恢复。检查使用独立用例模式。",
                    )
                  : t(
                      "With automatic devices, a device walk also runs on the other supported mobile platform. A pinned device limits the run to that device’s platform. The execution queue shows the actual expanded tasks.",
                      "自动选择设备时，设备回放也会在另一个受支持的移动平台运行。指定设备后，仅运行其平台。执行队列会显示服务端实际展开的任务。",
                    )}
              </p>
              {executionMode === "route" && (
                <div
                  aria-label={t("Route validation", "路线验证")}
                  aria-live="polite"
                >
                  {routeDeviceMismatch && (
                    <ErrorNotice>
                      {t(
                        "The selected device does not match this route. Choose an Android device or Auto.",
                        "所选设备与路线平台不符。请选择 Android 设备或自动选择。",
                      )}
                    </ErrorNotice>
                  )}
                  {routeError || routePlan?.reason ? (
                    <ErrorNotice>{routeError || routePlan?.reason}</ErrorNotice>
                  ) : (
                    <p className="muted">
                      {routePlan?.supported
                        ? t("Route ready", "路线可运行")
                        : t(
                            "Checking route compatibility…",
                            "正在检查路线是否兼容…",
                          )}
                    </p>
                  )}
                  {routePlan?.supported && (
                    <p className="muted">
                      {routePlan.platform} · {routePlan.setup}
                    </p>
                  )}
                  {!!routePlan?.segments?.length && (
                    <ol>
                      {routePlan.segments.map((segment) => (
                        <li key={segment.task_id}>
                          {title(catalog.data!, "edges", segment.edge_id, lang)}{" "}
                          · {segment.entry} → {segment.exit} · {segment.steps}{" "}
                          {t("steps", "步")}
                        </li>
                      ))}
                    </ol>
                  )}
                </div>
              )}
              <h3>
                {executionMode === "route"
                  ? t("Route order", "路线顺序")
                  : t("Selected tasks", "已选任务")}
              </h3>
              {draft.length ? (
                <div className="draft-list">
                  {draft.map((id, index) => (
                    <div className="row between" key={id}>
                      <span>
                        {executionMode === "route" ? `${index + 1}. ` : ""}
                        {label(id)}
                        <code>{id}</code>
                      </span>
                      <div className="row">
                        {executionMode === "route" && (
                          <>
                            <Button
                              size="icon"
                              aria-label={`${t("Move up", "上移")} ${label(id)}`}
                              disabled={index === 0}
                              onClick={() => move(index, -1)}
                            >
                              <ArrowUp size={14} />
                            </Button>
                            <Button
                              size="icon"
                              aria-label={`${t("Move down", "下移")} ${label(id)}`}
                              disabled={index === draft.length - 1}
                              onClick={() => move(index, 1)}
                            >
                              <ArrowDown size={14} />
                            </Button>
                          </>
                        )}
                        <Button
                          size="icon"
                          aria-label={`${t("Remove", "移除")} ${label(id)}`}
                          onClick={() => toggle(id)}
                        >
                          <X size={14} />
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="muted">
                  {t(
                    "Choose a path in the catalog, or add a host check below.",
                    "在目录选择路径，或添加下面的主机检查。",
                  )}
                </p>
              )}
              <h3>
                <Terminal size={16} />
                {t("Host & diagnostic checks", "主机与诊断检查")}
              </h3>
              {tasks.error && <ErrorNotice>{tasks.error}</ErrorNotice>}
              <div className="host-checks">
                {tasks.data?.runnable.map((task) => (
                  <div className="host-check" key={task.id}>
                    <label>
                      <input
                        type="checkbox"
                        disabled={executionMode === "route"}
                        checked={draft.includes(task.id)}
                        onChange={() => toggle(task.id)}
                      />
                      <span>
                        {task.label}
                        <small>
                          {task.platforms.join(" + ")}
                          {task.heavy
                            ? ` · ${t("Build workload", "构建任务")}`
                            : ""}
                        </small>
                      </span>
                    </label>
                    <details>
                      <summary>{t("Command", "命令")}</summary>
                      <pre>{task.cmd.join(" ")}</pre>
                      <CopyButton
                        value={task.cmd.join(" ")}
                        label={t("Copy command", "复制命令")}
                      />
                    </details>
                  </div>
                ))}
              </div>
              {!!tasks.data?.manual?.length && (
                <details>
                  <summary>{t("Manual tasks", "手动任务")}</summary>
                  <pre>{JSON.stringify(tasks.data.manual, null, 2)}</pre>
                  <CopyButton
                    value={JSON.stringify(tasks.data.manual, null, 2)}
                  />
                </details>
              )}
            </div>
            <footer className="settings-footer">
              <Button onClick={() => setDraft([])} disabled={!draft.length}>
                {t("Clear draft", "清空草稿")}
              </Button>
              <Button
                variant="default"
                disabled={!canStart}
                onClick={() => void start()}
              >
                <Play size={14} />
                {t("Start run", "开始运行")}
              </Button>
            </footer>
          </section>
        </SettingsModal>
      )}
    </>
  );
}
import { createContext, useContext } from "react";
const SetLanguage = createContext<(lang: Lang) => void>(() => {});
const ReactLanguage = () => useContext(Language);
function LanguageToggle() {
  const lang = ReactLanguage(),
    setLang = useContext(SetLanguage);
  return (
    <Button
      size="sm"
      variant="ghost"
      aria-label="Switch language / 切换语言"
      onClick={() => setLang(lang === "en" ? "zh" : "en")}
    >
      {lang === "en" ? "中文" : "EN"}
    </Button>
  );
}
export default function App() {
  const [lang, setLang] = useState<Lang>(() => {
    try {
      return localStorage.getItem("testmap-language") === "en"
        ? "en"
        : localStorage.getItem("testmap-language") === "zh"
          ? "zh"
          : navigator.language.startsWith("zh")
            ? "zh"
            : "en";
    } catch {
      return "en";
    }
  });
  useEffect(() => {
    document.documentElement.lang = lang;
    try {
      localStorage.setItem("testmap-language", lang);
    } catch {}
  }, [lang]);
  return (
    <Language.Provider value={lang}>
      <SetLanguage.Provider value={setLang}>
        <Workspace />
      </SetLanguage.Provider>
    </Language.Provider>
  );
}

function SettingsModal({
  children,
  close,
}: {
  children: React.ReactNode;
  close: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialog.current?.showModal();
    return () => dialog.current?.close();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="settings-backdrop"
      aria-label="Run settings / 运行设置"
      onCancel={close}
      onClick={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      {children}
    </dialog>
  );
}
