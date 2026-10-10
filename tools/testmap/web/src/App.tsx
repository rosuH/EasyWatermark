import { useEffect, useRef, useState } from "react";
import {
  ArrowRight,
  GitBranch,
  History,
  ListChecks,
  Play,
  RefreshCw,
  Settings2,
  Terminal,
  X,
} from "lucide-react";
import { post, useResource } from "./api";
import type {
  DevicesResponse,
  MapResponse,
  RunsResponse,
  RunStarted,
  TasksResponse,
} from "./api-types";
import { type Catalog, type Lang, duration, humanError, title } from "./model";
import { CatalogView } from "./Catalog";
import { RunView } from "./Run";
import { Button } from "./components/ui/button";
import { CopyButton, Empty, ErrorNotice, Language, Status, useT } from "./ui";
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
    );
  const [draft, setDraft] = useState<string[]>([]),
    [settings, setSettings] = useState(false),
    [device, setDevice] = useState("auto"),
    [repeat, setRepeat] = useState(1),
    [starting, setStarting] = useState(false),
    [error, setError] = useState("");
  const refresh = () => setRevision((r) => r + 1);
  const toggle = (id: string) =>
    setDraft((old) =>
      old.includes(id) ? old.filter((t) => t !== id) : [...old, id],
    );
  const openRun = (id: string) => {
    setRunId(id);
    setView("run");
  };
  const allDevices = Object.values(devices.data || {}).flat();
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
          <CatalogView
            catalog={catalog.data}
            map={map.data}
            draft={draft}
            toggle={toggle}
            refresh={refresh}
            openRun={openRun}
          />
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
            {repeat}× ·{" "}
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
            disabled={!draft.length || starting}
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
              <div className="settings-controls">
                <label>
                  {t("Device", "设备")}
                  <select
                    value={device}
                    onChange={(e) => setDevice(e.target.value)}
                  >
                    <option value="auto">
                      {t(
                        "Auto — available platforms in parallel",
                        "自动：可用平台并行",
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
                  {t("Repeat", "重复")}
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
                {t(
                  "With automatic devices, a device walk also runs on the other supported mobile platform. A pinned device limits the run to that device’s platform. The execution queue shows the actual expanded tasks.",
                  "自动选择设备时，设备回放也会在另一个受支持的移动平台运行。指定设备后，仅运行其平台。执行队列会显示服务端实际展开的任务。",
                )}
              </p>
              <h3>{t("Selected tasks", "已选任务")}</h3>
              {draft.length ? (
                <div className="draft-list">
                  {draft.map((id) => (
                    <div className="row between" key={id}>
                      <span>
                        {label(id)}
                        <code>{id}</code>
                      </span>
                      <Button
                        size="icon"
                        aria-label={`${t("Remove", "移除")} ${label(id)}`}
                        onClick={() => toggle(id)}
                      >
                        <X size={14} />
                      </Button>
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
                disabled={!draft.length || starting}
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
