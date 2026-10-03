import { useContext, useEffect, useMemo, useRef, useState } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  useNodesInitialized,
  useNodesState,
  useReactFlow,
  useStore,
  type EdgeProps,
  type Node,
  type Edge,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { ChevronRight, GitBranch, ListTree, Search, X } from "lucide-react";
import type { Confirmation, MapResponse, RunDetail } from "./api-types";
import {
  type Catalog as CatalogData,
  type Filters,
  emptyFilters,
  matches,
  title,
} from "./model";
import { Button } from "./components/ui/button";
import { Confirm } from "./Confirm";
import { useResource } from "./api";
import { CopyButton, Empty, Language, Screenshot, Status, useT } from "./ui";
function EdgeMarks({
  state,
  confirmation,
}: {
  state?: string;
  confirmation?: Confirmation;
}) {
  const t = useT();
  const result = state || "uncovered";
  const glyph =
    result === "passed"
      ? "✓"
      : ["failed", "blocked"].includes(result)
        ? "×"
        : result === "review_required"
          ? "◐"
          : ["running", "pending", "paused"].includes(result)
            ? "…"
            : "○";
  return (
    <>
      <span
        title={`${t("Result", "结果")}: ${result}`}
        aria-label={`${t("Result", "结果")}: ${result}`}
      >
        {glyph}
      </span>
      {confirmation && (
        <span
          title={
            confirmation.stale
              ? t("Human confirmation is stale", "人工确认已过期")
              : t("Human confirmed", "人工已确认")
          }
          aria-label={
            confirmation.stale
              ? t("Human confirmation is stale", "人工确认已过期")
              : t("Human confirmed", "人工已确认")
          }
        >
          {confirmation.stale ? "↻" : "✓"}
        </span>
      )}
    </>
  );
}
function PathEdge(props: EdgeProps) {
  const self = props.source === props.target;
  const index = Number(props.data?.pairIndex || 0);
  const count = Number(props.data?.pairCount || 1);
  const height = 95 + index * 44;
  const offset = (index - (count - 1) / 2) * 46;
  const reach = Math.max(65, Math.abs(props.targetX - props.sourceX) / 2);
  const [path, x, y] = self
    ? [
        `M${props.sourceX},${props.sourceY} C${props.sourceX + 130},${props.sourceY - height} ${props.targetX - 130},${props.targetY - height} ${props.targetX},${props.targetY}`,
        (props.sourceX + props.targetX) / 2,
        props.sourceY - height * 0.75,
      ]
    : count > 1
      ? [
          `M${props.sourceX},${props.sourceY} C${props.sourceX + reach},${props.sourceY + offset} ${props.targetX - reach},${props.targetY + offset} ${props.targetX},${props.targetY}`,
          (props.sourceX + props.targetX) / 2,
          (props.sourceY + props.targetY) / 2 + offset * 0.75,
        ]
      : getBezierPath(props);
  return (
    <>
      <BaseEdge
        id={props.id}
        path={path}
        style={props.style}
        interactionWidth={26}
      />
      <EdgeLabelRenderer>
        <div
          className="edge-label nodrag nopan"
          title={String(props.data?.title || props.id)}
          style={{
            transform: `translate(-50%, -50%) translate(${x}px,${y}px)`,
            pointerEvents: "all",
          }}
        >
          <button
            aria-label={String(props.data?.title || props.id)}
            onMouseEnter={() => (props.data?.hover as () => void)?.()}
            onMouseLeave={() => (props.data?.leave as () => void)?.()}
            onFocus={() => (props.data?.hover as () => void)?.()}
            onBlur={() => (props.data?.leave as () => void)?.()}
            onClick={() => (props.data?.choose as () => void)?.()}
          >
            {String(props.data?.number || "·")}
            <EdgeMarks
              state={props.data?.result as string | undefined}
              confirmation={
                props.data?.confirmation as Confirmation | undefined
              }
            />
          </button>
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
const edgeTypes = { path: PathEdge };
// fitView measures nodes only; reserve space for the self-loop arcs above them.
const graphFitOptions = {
  padding: { top: "28%", bottom: "8%", x: "6%" },
} as const;
export function FitOnResize() {
  const { fitView } = useReactFlow();
  const ready = useNodesInitialized();
  const width = useStore((s) => s.width),
    height = useStore((s) => s.height);
  const fittedSize = useRef("");
  useEffect(() => {
    const size = `${width}:${height}`;
    if (!ready || !width || !height || fittedSize.current === size) return;
    const frame = requestAnimationFrame(() => {
      fittedSize.current = size;
      void fitView({ ...graphFitOptions, duration: 0 });
    });
    return () => cancelAnimationFrame(frame);
  }, [width, height, ready, fitView]);
  return null;
}

// Controlled React Flow nodes must retain dimension changes. Rebuilding labels
// or focus styles without measured sizes resets nodesInitialized in 12.8.5.
export function useCatalogNodes(definitions: Node[]) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>(definitions);
  useEffect(() => {
    setNodes((previous) => {
      const measured = new Map(
        previous.map((node) => [node.id, node.measured]),
      );
      return definitions.map((node) => ({
        ...node,
        measured: measured.get(node.id),
      }));
    });
  }, [definitions, setNodes]);
  return { nodes, onNodesChange };
}
export function CatalogView({
  catalog,
  map,
  draft,
  toggle,
  refresh,
  openRun,
}: {
  catalog: CatalogData;
  map: MapResponse;
  draft: string[];
  toggle: (id: string) => void;
  refresh: () => void;
  openRun: (id: string) => void;
}) {
  const lang = useContext(Language),
    t = useT();
  const [mode, setMode] = useState<"map" | "tree">("map");
  const [filters, setFilters] = useState<Filters>(emptyFilters);
  const [selected, setSelected] = useState<string | null>(null),
    [hover, setHover] = useState<string | null>(null),
    [nodeId, setNode] = useState<string | null>(null);
  const filtered = useMemo(
    () => catalog.edges.filter((edge) => matches(edge, filters, catalog)),
    [catalog, filters],
  );
  const visibleIds = new Set(filtered.map((e) => e.id));
  const [hoverNode, setHoverNode] = useState<string | null>(null);
  const activeNode = hoverNode || nodeId;
  const active = hoverNode ? null : hover || selected;
  const edge = catalog.edges.find((e) => e.id === active);
  const platforms = [
    ...new Set(catalog.edges.flatMap((e) => Object.keys(e.platforms))),
  ];
  const boundRun = edge ? map.result_runs[edge.id] : undefined;
  const runResult = useResource<RunDetail>(
    boundRun ? `/api/runs/${encodeURIComponent(boundRun)}` : null,
    5000,
  );
  const choose = (id: string) => {
    setSelected(id);
    setNode(null);
  };
  const nodeDefinitions: Node[] = useMemo(() => {
    const ordered = [...catalog.nodes].sort((a, b) => {
      const ai = catalog.node_order.indexOf(a.id),
        bi = catalog.node_order.indexOf(b.id);
      return (
        (ai < 0 ? 999 : ai) - (bi < 0 ? 999 : bi) || a.id.localeCompare(b.id)
      );
    });
    // ponytail: stable four-column layout; use a graph layout engine for larger maps.
    return ordered.map((n, i) => ({
      id: n.id,
      position: { x: (i % 4) * 255, y: Math.floor(i / 4) * 160 },
      data: {
        label: (
          <>
            <span className="node-kind">
              {catalog.node_kinds[n.id] || t("Screen", "页面")}
            </span>
            <strong>{title(catalog, "nodes", n.id, lang)}</strong>
          </>
        ),
      },
      sourcePosition: "right" as Node["sourcePosition"],
      targetPosition: "left" as Node["targetPosition"],
      className:
        activeNode === n.id ||
        (edge && (edge.from === n.id || edge.to === n.id))
          ? "graph-node focused"
          : "graph-node",
      style: {
        opacity: edge && edge.from !== n.id && edge.to !== n.id ? 0.28 : 1,
      },
    }));
  }, [catalog, lang, activeNode, edge, t]);
  const { nodes, onNodesChange } = useCatalogNodes(nodeDefinitions);
  const flowEdges: Edge[] = catalog.edges
    .filter((e) => visibleIds.has(e.id))
    .map((e) => ({
      id: e.id,
      source: e.from,
      target: e.to,
      type: "path",
      data: {
        title: title(catalog, "edges", e.id, lang),
        number: catalog.edges.indexOf(e) + 1,
        result: map.results[e.id],
        confirmation: map.confirmations[e.id],
        pairIndex: catalog.edges
          .filter((other) => other.from === e.from && other.to === e.to)
          .findIndex((other) => other.id === e.id),
        pairCount: catalog.edges.filter(
          (other) => other.from === e.from && other.to === e.to,
        ).length,
        choose: () => choose(e.id),
        hover: () => setHover(e.id),
        leave: () => setHover(null),
      },
      style: {
        stroke:
          active === e.id || (activeNode && [e.from, e.to].includes(activeNode))
            ? "#91b5ff"
            : "#65717f",
        strokeWidth: active === e.id ? 2.5 : 1.3,
        opacity:
          (active && active !== e.id) ||
          (activeNode && ![e.from, e.to].includes(activeNode))
            ? 0.16
            : 1,
        strokeDasharray: Object.values(e.platforms).every(
          (p) => p.drive === "none",
        )
          ? "4 5"
          : undefined,
      },
    }));
  const options = (key: keyof Filters, values: string[], label: string) => (
    <label className="filter-select">
      <span>{label}</span>
      <select
        aria-label={label}
        value={filters[key]}
        onChange={(e) => setFilters({ ...filters, [key]: e.target.value })}
      >
        <option value="">{t("All", "全部")}</option>
        {values.map((v) => (
          <option key={v} value={v}>
            {v}
          </option>
        ))}
      </select>
    </label>
  );
  return (
    <div
      className="catalog-layout"
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          setSelected(null);
          setHover(null);
          setNode(null);
        }
      }}
    >
      <section className="catalog-main panel">
        <div className="panel-heading">
          <div>
            <span className="eyebrow">
              {t("PRODUCT COVERAGE", "产品路径覆盖")}
            </span>
            <h1>{t("Case catalog", "用例目录")}</h1>
          </div>
          <div className="segmented" aria-label={t("Catalog view", "目录视图")}>
            <Button
              variant="ghost"
              aria-pressed={mode === "map"}
              onClick={() => setMode("map")}
            >
              <GitBranch size={15} />
              {t("Map", "地图")}
            </Button>
            <Button
              variant="ghost"
              aria-pressed={mode === "tree"}
              onClick={() => setMode("tree")}
            >
              <ListTree size={15} />
              {t("Tree", "树")}
            </Button>
          </div>
        </div>
        <div className="filters">
          <label className="search">
            <Search size={16} />
            <input
              aria-label={t("Search paths and cases", "搜索路径与用例")}
              placeholder={t(
                "Search paths, cases, references…",
                "搜索路径、用例、引用…",
              )}
              value={filters.q}
              onChange={(e) => setFilters({ ...filters, q: e.target.value })}
            />
          </label>
          <div className="filter-row">
            {options("platform", platforms, t("Platform", "平台"))}
            {options(
              "layer",
              [
                ...new Set(
                  catalog.edges.flatMap((e) => e.cases.map((c) => c.layer)),
                ),
              ].sort(),
              t("Layer", "层级"),
            )}
            {options(
              "drive",
              [
                ...new Set(
                  catalog.edges.flatMap((e) =>
                    Object.values(e.platforms).map((p) => p.drive),
                  ),
                ),
              ],
              t("Drive", "驱动"),
            )}
            {options(
              "priority",
              [...new Set(catalog.edges.map((e) => e.priority))],
              t("Priority", "优先级"),
            )}
            <Button
              size="sm"
              variant="ghost"
              aria-label={t("Clear filters", "清除筛选")}
              onClick={() => setFilters(emptyFilters)}
            >
              <X size={14} />
            </Button>
            <span className="count">
              {filtered.length} / {catalog.edges.length} {t("paths", "条路径")}
            </span>
          </div>
        </div>
        {mode === "map" ? (
          <div className="graph" data-testid="catalog-graph">
            <ReactFlow
              key={lang}
              nodes={nodes}
              onNodesChange={onNodesChange}
              edges={flowEdges}
              edgeTypes={edgeTypes}
              fitView
              fitViewOptions={graphFitOptions}
              minZoom={0.25}
              maxZoom={2}
              nodesDraggable={false}
              nodesConnectable={false}
              elementsSelectable={false}
              onNodeMouseEnter={(_, n) => setHoverNode(n.id)}
              onNodeMouseLeave={() => setHoverNode(null)}
              onNodeClick={(_, n) => {
                setNode(n.id);
                setSelected(null);
              }}
              onEdgeClick={(_, e) => choose(e.id)}
              onEdgeMouseEnter={(_, e) => setHover(e.id)}
              onEdgeMouseLeave={() => setHover(null)}
              onPaneClick={() => {
                setSelected(null);
                setNode(null);
              }}
              proOptions={{ hideAttribution: true }}
            >
              <FitOnResize />
              <Background color="#2b3543" gap={20} />
              <Controls
                showInteractive={false}
                fitViewOptions={graphFitOptions}
              />
            </ReactFlow>
            <div className="graph-hint">
              {t(
                "Hover to explore · Click to pin · Drag to pan",
                "悬停预览 · 点击固定 · 拖动平移",
              )}
            </div>
          </div>
        ) : (
          <div className="tree">
            {catalog.nodes.map((n) => {
              const outgoing = filtered.filter((e) => e.from === n.id);
              return outgoing.length ? (
                <details key={n.id} open>
                  <summary>
                    {title(catalog, "nodes", n.id, lang)}
                    <span>{outgoing.length}</span>
                  </summary>
                  {outgoing.map((e) => (
                    <div
                      className={`tree-row ${selected === e.id ? "selected" : ""}`}
                      key={e.id}
                      onMouseEnter={() => setHover(e.id)}
                      onMouseLeave={() => setHover(null)}
                    >
                      <button
                        className="tree-path"
                        onClick={() => choose(e.id)}
                      >
                        <ChevronRight size={14} />
                        <span>
                          {title(catalog, "edges", e.id, lang)}
                          <small>{title(catalog, "nodes", e.to, lang)}</small>
                        </span>
                        <Status state={map.results[e.id]} />
                        {map.confirmations[e.id] && (
                          <span
                            title={map.confirmations[e.id].run_id}
                            aria-label={
                              map.confirmations[e.id].stale
                                ? t(
                                    "Human confirmation is stale",
                                    "人工确认已过期",
                                  )
                                : t("Human confirmed", "人工已确认")
                            }
                          >
                            {map.confirmations[e.id].stale ? "↻" : "✓"}
                          </span>
                        )}
                      </button>
                    </div>
                  ))}
                </details>
              ) : null;
            })}
            {!filtered.length && (
              <Empty
                title={t("No matching paths", "没有匹配路径")}
                detail={t("Try clearing a filter.", "请尝试清除筛选条件。")}
              />
            )}
          </div>
        )}
        <footer className="catalog-footer">
          <span>
            {catalog.nodes.length} {t("screens", "个页面")} ·{" "}
            {catalog.edges.reduce(
              (total, edge) => total + edge.cases.length,
              0,
            )}{" "}
            {t("case references", "个用例引用")}
          </span>
          <span>
            {t("Process success ≠ human confirmation", "进程成功 ≠ 人工确认")}
          </span>
        </footer>
      </section>
      <aside
        className="inspector panel"
        aria-label={t("Path details", "路径详情")}
      >
        {edge ? (
          <>
            <div className="inspector-heading">
              <span className="eyebrow">
                {selected === edge.id
                  ? t("PINNED PATH", "已固定路径")
                  : t("PATH PREVIEW", "路径预览")}
              </span>
              <h2>{title(catalog, "edges", edge.id, lang)}</h2>
              <div className="row between">
                <code>{edge.id}</code>
                <CopyButton value={edge.id} />
              </div>
              <Status state={map.results[edge.id]} />
            </div>
            <div className="inspector-content">
              <p className="route">
                {title(catalog, "nodes", edge.from, lang)} →{" "}
                {title(catalog, "nodes", edge.to, lang)}
              </p>
              <h3>{t("Add to run draft", "加入运行草稿")}</h3>
              {Object.keys(edge.platforms).map((p) => {
                const taskId = `edge:${edge.id}@${p}`,
                  plan = catalog.edge_plans[edge.id]?.[p],
                  agent = !!catalog.agent[edge.id]?.[p];
                return (
                  <div className="platform-plan" key={p}>
                    <strong>{p}</strong>
                    <label>
                      <input
                        type="checkbox"
                        disabled={!plan?.runnable_count}
                        checked={draft.includes(taskId)}
                        onChange={() => toggle(taskId)}
                      />
                      {t("Checks", "检查")}{" "}
                      <span className="muted">{plan?.runnable_count || 0}</span>
                    </label>
                    {agent && (
                      <label>
                        <input
                          type="checkbox"
                          checked={draft.includes(taskId + "#agent")}
                          onChange={() => toggle(taskId + "#agent")}
                        />
                        {t("Device walk", "设备回放")}
                      </label>
                    )}
                  </div>
                );
              })}
              <details className="technical">
                <summary>{t("Technical details", "技术详情")}</summary>
                <dl>
                  <dt>{t("Trigger", "触发器")}</dt>
                  <dd>
                    <code>
                      {edge.trigger.kind}: {edge.trigger.value}
                    </code>
                    <CopyButton value={edge.trigger.value} />
                  </dd>
                  <dt>{t("Owners", "维护位置")}</dt>
                  <dd>{edge.owners.join(", ")}</dd>
                  <dt>{t("Priority", "优先级")}</dt>
                  <dd>{edge.priority}</dd>
                </dl>
                {Object.entries(edge.platforms).map(([p, meta]) => (
                  <p key={p}>
                    <strong>{p}</strong> · {meta.drive}
                    <br />
                    <code>{meta.via}</code>
                  </p>
                ))}
              </details>
              {Object.entries(edge.platforms)
                .filter(
                  ([p, meta]) =>
                    catalog.agent[edge.id]?.[p] === true &&
                    meta.drive !== "real",
                )
                .map(([p]) => (
                  <p className="notice" key={p}>
                    {p}:{" "}
                    {t(
                      "Walking through system UI does not upgrade the declared drive to real.",
                      "走过系统界面不会把声明的 drive 升级为 real。",
                    )}
                  </p>
                ))}
              <h3>
                {t("Cases", "用例")}{" "}
                <span className="muted">{edge.cases.length}</span>
              </h3>
              <div className="case-list">
                {edge.cases.map((c, i) => (
                  <div className="case" key={c.ref + i}>
                    <span className="tag">{c.layer}</span>
                    <strong>{title(catalog, "cases", c.ref, lang)}</strong>
                    <small>
                      {c.platform || t("All platforms", "全部平台")}
                      {c.dimension ? ` · ${c.dimension}` : ""}
                    </small>
                    {runResult.data?.tasks
                      .flatMap((task) => task.cases || [])
                      .filter((item) => item.ref === c.ref)
                      .map((item, idx) => (
                        <div className="row" key={idx}>
                          <Status state={item.status} />
                          <small>{item.message}</small>
                        </div>
                      ))}
                    <div className="row">
                      <code>{c.ref}</code>
                      <CopyButton value={c.ref} />
                    </div>
                  </div>
                ))}
              </div>
              <h3>{t("Reference evidence", "参考证据")}</h3>
              <p className="muted">
                {t(
                  "Shared L1 witnesses; these are not tied to the selected run.",
                  "共享 L1 见证图，不绑定当前运行。",
                )}
              </p>
              <div className="thumbs">
                {(catalog.edge_plans[edge.id]?.witnesses || []).map((w) =>
                  map.witnesses.includes(w.file) ? (
                    <Screenshot
                      key={w.file}
                      src={`/witness/${encodeURIComponent(w.file)}`}
                      label={title(catalog, "cases", w.ref, lang)}
                    />
                  ) : (
                    <p key={w.file} className="muted">
                      {t("No image", "无截图")}: {w.file}
                    </p>
                  ),
                )}
              </div>
              {selected === edge.id ? (
                <Confirm
                  edge={edge}
                  map={map}
                  runResult={runResult}
                  refresh={refresh}
                  openRun={openRun}
                />
              ) : (
                <Button onClick={() => choose(edge.id)}>
                  {t("Pin path to inspect and confirm", "固定路径以检查和确认")}
                </Button>
              )}
            </div>
          </>
        ) : activeNode ? (
          <div className="inspector-content">
            <h2>{title(catalog, "nodes", activeNode, lang)}</h2>
            <p>
              <code>
                {catalog.nodes.find((n) => n.id === activeNode)?.source}
              </code>
            </p>
            {filtered
              .filter((e) => e.from === activeNode || e.to === activeNode)
              .map((e) => (
                <Button
                  className="w-full"
                  key={e.id}
                  onClick={() => choose(e.id)}
                >
                  {title(catalog, "edges", e.id, lang)}
                </Button>
              ))}
          </div>
        ) : (
          <Empty
            title={t("Follow a product path", "选择一条产品路径")}
            detail={t(
              "Hover over an edge to preview it. Click to inspect cases, add tasks, and review evidence.",
              "悬停连线可预览。点击后检查用例、加入任务并审阅证据。",
            )}
          />
        )}
      </aside>
    </div>
  );
}
