import { useContext, useEffect, useRef, useState } from "react";
import { ArrowLeft, FileText, Radio, RotateCcw, Square } from "lucide-react";
import { post, useResource, useVisible } from "./api";
import type {
  RunDetail,
  StatusResponse,
  StatusTask,
  Step,
  Watch,
} from "./api-types";
import {
  type Catalog,
  duration,
  humanError,
  safeHref,
  stepHref,
  title,
} from "./model";
import { watchMedia, type VideoDiagnostic } from "./media";
import { Button } from "./components/ui/button";
import {
  CopyButton,
  Empty,
  ErrorNotice,
  Language,
  Screenshot,
  Status,
  useT,
} from "./ui";
export const taskKey = (task: StatusTask) =>
  `${task.id}:${task.repeat?.k || 1}`;
export function DeviceLane({
  platform,
  watch,
  task,
  runId,
  live,
  onFollowLive,
}: {
  platform: string;
  watch?: Watch;
  task?: StatusTask;
  runId: string;
  live: boolean;
  onFollowLive?: () => void;
}) {
  const t = useT();
  const [selected, setSelected] = useState<Step | null>(null),
    [media, setMedia] = useState("connecting"),
    [retry, setRetry] = useState(0);
  const [videoDiagnostic, setVideoDiagnostic] = useState<
    (VideoDiagnostic & { taskId: string; runId: string }) | null
  >(null);
  const canvas = useRef<HTMLCanvasElement>(null),
    currentStep = useRef<HTMLButtonElement>(null);
  const visible = useVisible();
  const taskId = task ? taskKey(task) : "";
  useEffect(() => {
    setSelected(null);
    setVideoDiagnostic(null);
  }, [runId, taskId]);
  const streaming =
    live &&
    visible &&
    !selected &&
    !!watch &&
    !!task &&
    ["running", "paused"].includes(task.state);
  useEffect(() => {
    if (!streaming || !watch || !canvas.current) return;
    setVideoDiagnostic(null);
    return watchMedia(
      canvas.current,
      platform,
      watch.device,
      setMedia,
      (diagnostic) => setVideoDiagnostic({ ...diagnostic, taskId, runId }),
    );
  }, [streaming, platform, watch?.device, retry, runId, taskId]);
  const current = task?.steps?.find((s) => s.state === "current");
  useEffect(() => {
    if (!selected && live && currentStep.current) {
      const parent = currentStep.current.parentElement;
      if (parent) {
        const top = currentStep.current.offsetTop - parent.offsetTop;
        parent.scrollTop = Math.max(0, top - parent.clientHeight / 2);
      }
    }
  }, [current?.n, taskId, live, selected]);
  const historical =
    selected ||
    (!live
      ? [...(task?.steps || [])].reverse().find((s) => s.shot) ||
        task?.steps?.at(-1)
      : undefined);
  const source = historical?.shot ? stepHref(runId, historical.shot) : null;
  const [imageFailed, setImageFailed] = useState(false);
  useEffect(() => setImageFailed(false), [source]);
  return (
    <section
      className="device-lane panel"
      aria-label={`${platform} ${t("device", "设备")}`}
    >
      <header className="lane-header">
        <div>
          <strong>{platform}</strong>
          <small>
            {watch?.name ||
              watch?.device ||
              t("No active device", "没有活动设备")}
          </small>
        </div>
        {streaming ? (
          <span className="live-label">
            <Radio size={13} />
            {media === "video"
              ? "H.264"
              : media === "still"
                ? t("Still frames", "静帧")
                : t("Connecting", "连接中")}
          </span>
        ) : (
          <span className="tag">{t("Evidence", "证据")}</span>
        )}
      </header>
      <div className="device-stage">
        {streaming ? (
          <>
            <canvas
              ref={canvas}
              aria-label={`${platform} ${t("live screen", "实时画面")}`}
              style={{
                display: ["video", "still"].includes(media) ? "block" : "none",
              }}
            />
            {!["video", "still"].includes(media) && (
              <Empty
                title={
                  media === "disconnected"
                    ? t("Device stream disconnected", "设备画面已断开")
                    : t("Waiting for a device frame", "等待设备画面")
                }
                detail={t(
                  "No frame is being substituted.",
                  "不会使用其他画面替代。",
                )}
              />
            )}
          </>
        ) : source && !imageFailed ? (
          <img
            src={source}
            alt={`${platform} · ${historical?.text || historical?.n}`}
            onError={() => setImageFailed(true)}
          />
        ) : (
          <Empty
            title={t("No screenshot", "无截图")}
            detail={
              historical?.shot_error ||
              task?.note ||
              t(
                "Captured evidence will appear here when it is available.",
                "有截图证据时会在此显示。",
              )
            }
          />
        )}
      </div>
      {!streaming && historical?.shot && (
        <p className="muted" role="note">
          {historical.shot_capture?.method === "agent-device-inline" &&
          historical.shot_capture.timing === "after-step-before-next-action"
            ? t(
                "Captured after this step, before the next action.",
                "在本步骤完成后、下一条操作前截图。",
              )
            : t("Screenshot timing unverified", "截图时刻未验证")}
        </p>
      )}
      {!selected &&
        videoDiagnostic?.taskId === taskId &&
        videoDiagnostic.runId === runId && (
          <details>
            <summary>
              {t("Last video fallback details", "最近视频降级详情")}
            </summary>
            <pre className="log">
              {[
                `${t("Reason", "原因")}: ${videoDiagnostic.reason}`,
                `${t("Elapsed", "耗时")}: ${videoDiagnostic.elapsedMs} ms`,
                `${t("First packet / IDR / output", "首包 / 关键帧 / 输出")}: ${[videoDiagnostic.firstPacketMs, videoDiagnostic.firstIdrMs, videoDiagnostic.firstOutputMs].map((value) => (value === null ? "—" : `${value} ms`)).join(" / ")}`,
                `${t("HTTP / first NAL / parameters / decode submitted", "HTTP / 首 NAL / 参数就绪 / 提交解码")}: ${[videoDiagnostic.responseMs, videoDiagnostic.firstNalMs, videoDiagnostic.parametersReadyMs, videoDiagnostic.firstDecodeMs].map((value) => (value == null ? "—" : `${value} ms`)).join(" / ")}`,
                `Codec: ${videoDiagnostic.codec}`,
                `${t("Decode / output / peak queue", "提交解码 / 输出 / 队列峰值")}: ${videoDiagnostic.decodeCount} / ${videoDiagnostic.outputCount} / ${videoDiagnostic.maxQueue}`,
                videoDiagnostic.errorName
                  ? `${videoDiagnostic.errorName}: ${videoDiagnostic.errorMessage || ""}`
                  : "",
              ]
                .filter(Boolean)
                .join("\n")}
            </pre>
          </details>
        )}
      <div className="lane-caption">
        <span>
          {selected
            ? `${t("Step", "步骤")} ${selected.n} · ${t("pinned evidence", "固定证据")}`
            : task?.label || t("No task for this lane", "此设备通道没有任务")}
        </span>
        {selected ? (
          <Button
            size="sm"
            onClick={() => {
              setSelected(null);
              if (live) onFollowLive?.();
            }}
          >
            <ArrowLeft size={13} />
            {live
              ? t("Follow live", "跟随实时")
              : t("Latest evidence", "最新证据")}
          </Button>
        ) : (
          streaming && (
            <Button
              size="sm"
              aria-label={t("Reconnect stream", "重新连接画面")}
              onClick={() => setRetry((v) => v + 1)}
            >
              <RotateCcw size={13} />
            </Button>
          )
        )}
      </div>
      <div
        className="step-list"
        aria-label={`${platform} ${t("steps", "步骤")}`}
      >
        {task?.steps?.length ? (
          task.steps.map((step, i) => (
            <button
              ref={step.state === "current" ? currentStep : undefined}
              className={`step ${selected?.n === step.n ? "selected" : ""}`}
              key={`${step.n}:${i}`}
              onClick={() => setSelected({ ...step })}
            >
              <span className="step-number">{step.n}</span>
              <span>
                <strong>{step.text || `${step.command} ${step.args}`}</strong>
                <small>
                  {step.command} {step.args}
                  {step.duration_ms != null ? ` · ${step.duration_ms} ms` : ""}
                </small>
                {step.shot_error && (
                  <small className="danger-text">{step.shot_error}</small>
                )}
              </span>
              <Status state={step.state} />
            </button>
          ))
        ) : (
          <p className="muted">
            {t("No steps recorded for this lane.", "此设备通道尚无步骤记录。")}
          </p>
        )}
      </div>
    </section>
  );
}
export function RunView({
  runId,
  platforms,
  catalog,
  onCurrent,
}: {
  runId: string | null;
  platforms: string[];
  catalog: Catalog;
  onCurrent: () => void;
}) {
  const t = useT(),
    lang = useContext(Language);
  const [revision, setRevision] = useState(0),
    [error, setError] = useState(""),
    [stopping, setStopping] = useState(false);
  const status = useResource<StatusResponse>(
    runId ? `/api/status?id=${encodeURIComponent(runId)}` : "/api/status",
    1000,
    revision,
  );
  const st = status.data;
  const detail = useResource<RunDetail>(
    st?.id ? `/api/runs/${encodeURIComponent(st.id)}` : null,
    st?.active ? 3000 : 0,
    revision,
  );
  const [selections, setSelections] = useState<Record<string, string>>({});
  useEffect(() => setSelections({}), [st?.id]);
  const tasks = st?.queue || [];
  const live = !!st?.active && !runId && !status.error;
  const stop = async () => {
    setStopping(true);
    setError("");
    try {
      await post("/api/stop", {});
      setRevision((v) => v + 1);
    } catch (e) {
      setError(humanError(e, lang));
    } finally {
      setStopping(false);
    }
  };
  const getTask = (p: string) => {
    const mine = tasks.filter((task) => task.platform === p);
    return (
      mine.find((task) => taskKey(task) === selections[p]) ||
      mine.find((task) => ["running", "paused"].includes(task.state)) ||
      [...mine].reverse().find((task) => task.steps?.length) ||
      mine[0]
    );
  };
  const evidence = detail.data;
  const failShots = (evidence?.tasks || []).flatMap((task) =>
    (task.steps || [])
      .filter((step) => step.shot && step.state === "failed")
      .map((step) => ({ task, step })),
  );
  return (
    <div className="run-view">
      <div className="page-heading">
        <div>
          <span className="eyebrow">
            {runId ? t("RECORDED RUN", "历史运行") : t("EXECUTION", "执行")}
          </span>
          <h1>
            {runId
              ? t("Run evidence", "运行证据")
              : t("Run monitor", "运行监看")}
          </h1>
          <p className="muted">
            <code>{st?.id || t("No current run", "当前没有运行")}</code>
            {st?.git
              ? ` · ${st.git.sha.slice(0, 8)}${st.git.dirty ? " + dirty" : ""}`
              : ""}
          </p>
        </div>
        <div className="row">
          {runId && (
            <Button onClick={onCurrent}>
              <ArrowLeft size={14} />
              {t("Current run", "当前运行")}
            </Button>
          )}
          <Status state={st?.state || "idle"} />
          {!runId && (
            <Button
              variant="destructive"
              disabled={!st?.active || stopping}
              onClick={() => void stop()}
            >
              <Square size={13} />
              {stopping ? t("Stopping…", "正在停止…") : t("Stop", "停止")}
            </Button>
          )}
        </div>
      </div>
      {(status.error || error) && (
        <ErrorNotice>
          {error ||
            `${t("Connection lost. Showing the last received state.", "连接已断开，显示上次接收的状态。")} ${status.error}`}
        </ErrorNotice>
      )}
      {st?.package_refusal && <ErrorNotice>{st.package_refusal}</ErrorNotice>}
      <div className="progress-card">
        <span>
          {st?.progress.done || 0} / {st?.progress.total || 0}{" "}
          {t("tasks complete", "个任务完成")}
        </span>
        <span>
          {st?.fail_count || 0} {t("failed", "失败")} ·{" "}
          {st?.uncovered_count || 0} {t("uncovered", "未覆盖")}
        </span>
        <div className="progress-track">
          <i
            style={{
              transform: `scaleX(${st?.progress.total ? st.progress.done / st.progress.total : 0})`,
            }}
          />
        </div>
      </div>
      <div className="execution-layout">
        <aside className="task-list panel">
          <div className="section-heading">
            <h3>{t("Execution queue", "执行队列")}</h3>
            <span className="tag">{tasks.length}</span>
          </div>
          {tasks.length ? (
            tasks.map((task, i) => (
              <button
                key={`${taskKey(task)}:${i}`}
                className={`task-row ${taskKey(task) === selections[task.platform] ? "selected" : ""}`}
                onClick={() =>
                  setSelections({
                    ...selections,
                    [task.platform]: taskKey(task),
                  })
                }
              >
                <Status state={task.state} />
                <strong>
                  {task.edge_id
                    ? title(catalog, "edges", task.edge_id, lang)
                    : task.label}
                </strong>
                <span>
                  {task.platform || t("Host", "主机")} · {task.repeat?.k || 1}/
                  {task.repeat?.n || 1} · {duration(task.duration_s)}
                </span>
                {task.note && (
                  <small className="danger-text">{task.note}</small>
                )}
              </button>
            ))
          ) : (
            <Empty
              title={t("Queue is empty", "队列为空")}
              detail={t(
                "Choose paths or host checks, then start a run.",
                "选择路径或主机检查后开始运行。",
              )}
            />
          )}
        </aside>
        <div className="device-grid">
          {platforms.map((p) => (
            <DeviceLane
              key={`${st?.id || "idle"}:${p}`}
              platform={p}
              watch={st?.watches?.[p as keyof typeof st.watches]}
              task={getTask(p)}
              runId={st?.id || ""}
              live={live}
              onFollowLive={() =>
                setSelections((previous) => {
                  const next = { ...previous };
                  delete next[p];
                  return next;
                })
              }
            />
          ))}
        </div>
      </div>
      <section className="panel diagnostics">
        <div className="section-heading">
          <h2>
            <FileText size={18} />
            {t("Evidence & diagnostics", "证据与诊断")}
          </h2>
          <CopyButton
            value={(st?.log_tail || []).join("\n")}
            label={t("Copy log", "复制日志")}
          />
        </div>
        <p className="muted">
          {st?.semantics ||
            t(
              "Execution, script checks, agent observation, independent review and human confirmation are separate records.",
              "执行、脚本检查、代理观察、独立审阅和人工确认分别记录。",
            )}
        </p>
        <div className="package-grid">
          {Object.entries(st?.packages || {}).map(
            ([p, pkg]) =>
              pkg && (
                <details key={p}>
                  <summary>
                    {p} · {pkg.version}
                  </summary>
                  <p>
                    <code>{pkg.package}</code>
                  </p>
                  <p>{pkg.artifact}</p>
                  <code>{pkg.sha256}</code>
                </details>
              ),
          )}
        </div>
        <details open={!st?.active}>
          <summary>
            {t("Run log (latest lines)", "运行日志（末尾内容）")}
          </summary>
          <pre className="log">
            {st?.log_tail?.join("\n") || t("No log output yet.", "尚无日志。")}
          </pre>
        </details>
        {detail.error && <ErrorNotice>{detail.error}</ErrorNotice>}
        {evidence?.tasks.map((task, i) => (
          <details key={task.id + i} className="task-evidence">
            <summary>
              <Status state={task.state} />
              {task.label || task.id} · {task.platform}
            </summary>
            <p>{task.note}</p>
            <pre>{task.cmd?.join(" ")}</pre>
            <CopyButton
              value={task.cmd?.join(" ") || ""}
              label={t("Copy command", "复制命令")}
            />
            {task.layers && (
              <dl className="layer-grid">
                {Object.entries(task.layers).map(([k, v]) => (
                  <div key={k}>
                    <dt>{k.replaceAll("_", " ")}</dt>
                    <dd>
                      {typeof v === "object" ? JSON.stringify(v) : String(v)}
                    </dd>
                  </div>
                ))}
              </dl>
            )}
            <ul className="result-cases">
              {task.cases?.map((c, i) => (
                <li key={c.ref + i}>
                  <Status state={c.status} />
                  <span>
                    {title(catalog, "cases", c.ref || c.name, lang)}
                    <small>{c.message}</small>
                  </span>
                </li>
              ))}
            </ul>
            <div className="evidence-links">
              {task.evidence_links?.map((link) => (
                <a
                  key={link.href}
                  href={safeHref(link.href)}
                  target="_blank"
                  rel="noreferrer"
                >
                  {link.name}
                </a>
              ))}
            </div>
            <div className="thumbs">
              {task.steps
                ?.filter((s) => s.shot)
                .map((s) => (
                  <Screenshot
                    key={s.n}
                    src={stepHref(evidence.id, s.shot!)}
                    label={`${task.platform} · ${s.n} · ${s.text}`}
                  />
                ))}
            </div>
          </details>
        ))}
        {!!failShots.length && (
          <>
            <h3>{t("Failure screenshots", "失败截图")}</h3>
            <div className="thumbs">
              {failShots.map(({ task, step }, i) => (
                <Screenshot
                  key={i}
                  src={stepHref(evidence!.id, step.shot!)}
                  label={`${task.platform} · ${step.text}`}
                />
              ))}
            </div>
          </>
        )}
        {!runId && st?.active && st.live?.preview?.exists && (
          <details>
            <summary>
              {t(
                "L1 render preview — not a device stream",
                "L1 渲染预览：不是设备画面",
              )}
            </summary>
            <Screenshot
              src={`/artifacts/live/preview.png?t=${st.live.preview.mtime_ms}`}
              label={st.live.preview.test || "L1"}
            />
            <div className="thumbs">
              {st.live.keyframes?.map((frame) => (
                <Screenshot
                  key={frame.file}
                  src={`/artifacts/keyframes/${encodeURIComponent(frame.file)}`}
                  label={frame.file}
                />
              ))}
            </div>
          </details>
        )}
      </section>
    </div>
  );
}
