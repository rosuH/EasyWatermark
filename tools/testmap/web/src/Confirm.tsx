import { useContext, useEffect, useState } from "react";
import { ShieldCheck, Undo2 } from "lucide-react";
import { ApiError, post, useResource } from "./api";
import type {
  Confirmation,
  MapEdge,
  MapResponse,
  RunDetail,
} from "./api-types";
import { canConfirm, edgeTasks, humanError, stepHref } from "./model";
import { Button } from "./components/ui/button";
import { ErrorNotice, Language, Screenshot, Status, useT } from "./ui";
export function Confirm({
  edge,
  map,
  refresh,
  openRun,
  runResult,
}: {
  edge: MapEdge;
  map: MapResponse;
  refresh: () => void;
  openRun: (id: string) => void;
  runResult?: { data?: RunDetail; error?: string };
}) {
  const t = useT(),
    lang = useContext(Language);
  const runId = map.result_runs[edge.id];
  const loaded = useResource<RunDetail>(
    runId && !runResult ? `/api/runs/${encodeURIComponent(runId)}` : null,
    5000,
  );
  const result = runResult || loaded;
  const confirmation = map.confirmations[edge.id];
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [revoking, setRevoking] = useState(false);
  const [undo, setUndo] = useState<{
    edgeId: string;
    previous: Confirmation | undefined;
  } | null>(null);
  useEffect(() => {
    if (!undo) return;
    const timer = setTimeout(() => setUndo(null), 5000);
    return () => clearTimeout(timer);
  }, [undo]);
  useEffect(() => {
    setRevoking(false);
    setError("");
    setUndo(null);
  }, [edge.id]);
  const write = async (payload: {
    edge_id: string;
    run_id?: string;
    revoke?: boolean;
  }) => {
    const token =
      document.querySelector<HTMLMetaElement>('meta[name="ewm-confirm-token"]')
        ?.content || "";
    if (!token) throw new ApiError(403, "");
    await post("/api/confirm", { ...payload, token });
  };
  // Only these explicit user click handlers write confirmation state. No effects do.
  const act = async (operation: "confirm" | "revoke" | "undo") => {
    const shownRun = runId;
    const previous = confirmation;
    setBusy(true);
    setError("");
    try {
      if (operation === "confirm" && shownRun) {
        await write({ edge_id: edge.id, run_id: shownRun });
        setUndo({ edgeId: edge.id, previous });
      }
      if (operation === "revoke") {
        await write({ edge_id: edge.id, revoke: true });
        setRevoking(false);
        setUndo(null);
      }
      if (operation === "undo" && undo) {
        await write(
          undo.previous
            ? { edge_id: undo.edgeId, run_id: undo.previous.run_id }
            : { edge_id: undo.edgeId, revoke: true },
        );
        setUndo(null);
      }
      refresh();
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 403
          ? t(
              "This page can no longer confirm. Refresh the page, then try again.",
              "此页面已无法确认。请刷新页面后重试。",
            )
          : humanError(e, lang),
      );
    } finally {
      setBusy(false);
    }
  };
  const tasks = edgeTasks(result.data, edge.id),
    covered = new Set(tasks.map((task) => task.platform).filter(Boolean));
  const current =
    !!confirmation && confirmation.run_id === runId && !confirmation.stale;
  return (
    <section
      className="confirm-box"
      aria-label={t("Human confirmation", "人工确认")}
    >
      <h3>
        <ShieldCheck size={16} />
        {t("Human confirmation", "人工确认")}
      </h3>
      <p className="muted">
        {t(
          "Automation records evidence. You decide whether the behavior is correct.",
          "自动化记录证据，由你判断行为是否正确。",
        )}
      </p>
      {!runId ? (
        <p>
          {t(
            "No run covers this path yet. Add it to the draft below.",
            "还没有运行覆盖这条路径，请加入运行草稿。",
          )}
        </p>
      ) : (
        <>
          <div className="row between">
            <code title={runId}>{runId}</code>
            <Status state={map.results[edge.id]} />
          </div>
          <p className="muted">
            {result.data?.started} · {result.data?.git?.sha?.slice(0, 8)}
            {result.data?.git?.dirty ? " + dirty" : ""}
          </p>
          {result.error && <ErrorNotice>{result.error}</ErrorNotice>}
          {tasks.map((task, i) => (
            <div className="row between" key={task.id + i}>
              <span>{task.platform || t("Host", "主机")}</span>
              <Status state={task.state} />
            </div>
          ))}
          {Object.entries(edge.platforms)
            .filter(([, meta]) => meta.drive !== "none")
            .map(
              ([p]) =>
                !covered.has(p as never) && (
                  <p className="muted" key={p}>
                    {p} {t("is not included in this run", "未包含在此运行中")}
                  </p>
                ),
            )}
          {!!covered.size && (
            <p className="muted">
              {t("Based on", "基于")} {[...covered].join(" + ")}
            </p>
          )}
          <div className="thumbs">
            {tasks.flatMap((task) =>
              (task.steps || [])
                .filter((s) => s.shot)
                .filter(
                  (s, i, shots) =>
                    s.state === "failed" || i === shots.length - 1,
                )
                .slice(-2)
                .map((s) => (
                  <Screenshot
                    key={task.id + s.n}
                    src={stepHref(runId, s.shot!)}
                    label={`${task.platform} · ${s.text || s.n} · ${s.shot_capture?.method === "agent-device-inline" && s.shot_capture.timing === "after-step-before-next-action" ? t("After step", "步骤后截图") : t("Screenshot timing unverified", "截图时刻未验证")}`}
                  />
                )),
            )}
          </div>
          <Button size="sm" onClick={() => openRun(runId)}>
            {t("Inspect run evidence", "查看运行证据")}
          </Button>
          {confirmation && (
            <div
              className={
                current ? "confirmation-current" : "confirmation-stale"
              }
            >
              {current ? "✓ " : ""}
              {t("Confirmed", "已确认")} <code>{confirmation.run_id}</code>
              <p>{confirmation.confirmed_at}</p>
              {!current && (
                <strong>
                  {t(
                    "Stale — latest run needs a new review",
                    "已过期：最新运行需要重新审阅",
                  )}
                </strong>
              )}
            </div>
          )}
          {!current && (
            <Button
              variant="default"
              disabled={
                busy || !!result.error || !canConfirm(result.data, edge.id)
              }
              onClick={() => void act("confirm")}
            >
              {t("Confirm", "确认")} {runId.slice(-8)}
            </Button>
          )}
          {!current && !canConfirm(result.data, edge.id) && (
            <p className="muted">
              {t(
                "Only completed passed or review-required results can be confirmed.",
                "仅已完成且通过或待审的结果可以确认。",
              )}
            </p>
          )}
        </>
      )}
      {confirmation && (
        <div className="row">
          {revoking ? (
            <>
              <Button
                variant="destructive"
                disabled={busy}
                onClick={() => void act("revoke")}
              >
                {t("Confirm revocation", "确定撤销")}
              </Button>
              <Button disabled={busy} onClick={() => setRevoking(false)}>
                {t("Cancel", "取消")}
              </Button>
            </>
          ) : (
            <Button size="sm" disabled={busy} onClick={() => setRevoking(true)}>
              {t("Revoke confirmation", "撤销确认")}
            </Button>
          )}
        </div>
      )}
      {undo && (
        <div className="undo" role="status">
          <span>{t("Confirmation saved", "确认已保存")}</span>
          <Button size="sm" disabled={busy} onClick={() => void act("undo")}>
            <Undo2 size={14} />
            {t("Undo", "撤销")}
          </Button>
        </div>
      )}
      {error && <ErrorNotice>{error}</ErrorNotice>}
    </section>
  );
}
