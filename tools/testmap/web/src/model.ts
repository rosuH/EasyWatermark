import type { MapEdge, MapNode, RunDetail, StatusTask } from "./api-types";
export type Lang = "en" | "zh";
export type Copy = Record<
  "nodes" | "edges" | "cases",
  Record<string, Partial<Record<Lang, string>>>
>;
export interface Plan {
  runnable_count: number;
  host_count: number;
  device_count: number;
  runnable_refs: string[];
  skipped_refs: { ref: string; reason: string }[];
}
export interface Catalog {
  nodes: MapNode[];
  edges: MapEdge[];
  copy: Copy;
  total: number;
  summary: Record<string, unknown>;
  edge_plans: Record<
    string,
    Record<string, Plan> & { witnesses: { ref: string; file: string }[] }
  >;
  agent: Record<
    string,
    Record<string, boolean | string | Record<string, string>>
  >;
  artemis: Record<string, unknown>;
  node_kinds: Record<string, string>;
  node_order: string[];
  layer_titles: Record<string, Partial<Record<Lang, string>>>;
}
export function title(
  catalog: Catalog,
  kind: keyof Copy,
  id: string,
  lang: Lang,
) {
  return (
    catalog.copy?.[kind]?.[id]?.[lang] || catalog.copy?.[kind]?.[id]?.en || id
  );
}
export interface Filters {
  q: string;
  platform: string;
  layer: string;
  drive: string;
  priority: string;
}
export const emptyFilters: Filters = {
  q: "",
  platform: "",
  layer: "",
  drive: "",
  priority: "",
};
export function matches(edge: MapEdge, filters: Filters, catalog: Catalog) {
  const { q, platform, layer, drive, priority } = filters;
  return (
    (!priority || edge.priority === priority) &&
    (!platform || !!edge.platforms[platform as keyof typeof edge.platforms]) &&
    (!drive ||
      Object.entries(edge.platforms).some(
        ([p, value]) => (!platform || platform === p) && value.drive === drive,
      )) &&
    (!layer ||
      edge.cases.some(
        (c) =>
          c.layer === layer &&
          (!platform || !c.platform || c.platform === platform),
      )) &&
    (!q ||
      JSON.stringify([
        edge,
        catalog.copy?.edges?.[edge.id],
        edge.cases.map((c) => catalog.copy?.cases?.[c.ref]),
      ])
        .toLowerCase()
        .includes(q.toLowerCase()))
  );
}
export function edgeTasks(run: RunDetail | undefined, edgeId: string) {
  return (run?.tasks || []).filter(
    (t) => (t.edge || t.edge_id || t.id.slice(5).split("@")[0]) === edgeId,
  );
}
export function canConfirm(run: RunDetail | undefined, edgeId: string) {
  if (
    !run ||
    ["running", "pending", "paused", "interrupted"].includes(run.state)
  )
    return false;
  const tasks = edgeTasks(run, edgeId).filter(
    (t) => !(t.state === "stopped" && t.duration_s == null),
  );
  return (
    tasks.length > 0 &&
    tasks.every((t) => ["passed", "review_required"].includes(t.state))
  );
}
export function laneTask(
  tasks: StatusTask[],
  platform: string,
  selected?: string,
) {
  const lane = tasks.filter((t) => t.platform === platform);
  return (
    lane.find((t) => t.id === selected) ||
    lane.find((t) => ["running", "paused"].includes(t.state)) ||
    [...lane].reverse().find((t) => t.steps?.length) ||
    lane[0]
  );
}
export function stepHref(run: string, shot: string) {
  return `/api/runs/${encodeURIComponent(run)}/steps/${encodeURIComponent(shot)}`;
}
export function safeHref(href: string) {
  return href.startsWith("/") && !href.startsWith("//") ? href : undefined;
}
export function humanError(error: unknown, lang: Lang) {
  return error instanceof Error
    ? error.message
    : lang === "zh"
      ? "请求失败"
      : "Request failed";
}
export function duration(seconds: number | null | undefined) {
  return seconds == null
    ? "—"
    : seconds < 60
      ? `${seconds.toFixed(1)}s`
      : `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`;
}
