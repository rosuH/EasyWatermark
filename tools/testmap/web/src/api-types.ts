// Testmap API types, checked against the Python console contract.
// Shapes follow scripts/testmap_console.py and the run/device producers.
// Platform ids are supplied by the catalog; this client does not own that list.

export type Platform = string;
export type Drive = "real" | "seam" | "none";
export type Priority = "core" | "normal" | "edge";
export type Layer = "L0" | "L1" | "L2" | "L3";

/** Documented: pending|running|passed|review_required|failed|skipped|stopped.
 *  Also seen in real records: interrupted (reap_stale_run), uncovered; paused/blocked (code). */
export type RunState =
  | "pending"
  | "running"
  | "paused"
  | "passed"
  | "review_required"
  | "failed"
  | "skipped"
  | "stopped"
  | "interrupted"
  | "uncovered"
  | "blocked";
export type StatusState = RunState | "idle";
export type StepState = "pending" | "current" | "done" | "failed";
export type RunSource = "manual" | "select" | "verify" | (string & {}); // one historical record stores a file path
export type CaseStatus =
  | "passed"
  | "failed"
  | "skipped"
  | "review_required"
  | "interrupted"
  | "not-in-report";

export interface GitInfo {
  sha: string;
  dirty: boolean;
}

// ---------- GET /api/map ----------
export interface MapCase {
  ref: string;
  layer: Layer;
  platform?: Platform; // absent = all platforms
  dimension?: "perf";
}
export interface EdgePlatform {
  drive: Drive;
  via: string;
}
export interface MapEdge {
  id: string;
  from: string;
  to: string;
  trigger: { kind: "tag" | "system"; value: string };
  platforms: Record<Platform, EdgePlatform>;
  owners: string[];
  priority: Priority;
  cases: MapCase[];
}
/** NOTE: no title / column / layout here. Titles (copy.yaml) and columns are only
 *  embedded into map.html by generate_testmap.py. */
export interface MapNode {
  id: string;
  source: string;
  states: string[];
  screens_ref: string[];
}
export interface Confirmation {
  // (code) confirmation_views
  edge_id: string;
  run_id: string;
  confirmed_at: string; // ISO-8601 Z
  actor: "human" | (string & {});
  stale: boolean;
  latest_run: string | null;
}
export interface MapResponse {
  nodes: MapNode[]; // 15
  edges: MapEdge[]; // 29
  badges: Record<string, "passed" | "failed">; // edge id -> newest L1/guard result
  results: Record<string, RunState>; // edge id -> worst task state in newest covering run
  confirmations: Record<string, Confirmation>;
  witnesses: string[]; // basenames under /witness/
  latest_run: string | null;
  /** edge id -> newest covering run id. Matches the same projection as results. */
  result_runs: Record<string, string>;
  head: GitInfo;
}

// ---------- run records ----------
export interface Step {
  n: number;
  text: string;
  command: string;
  args: string;
  platform: Platform;
  state: StepState;
  duration_ms?: number;
  shot?: string; // file under /api/runs/<id>/steps/<shot>
  shot_capture?: {
    method: string;
    timing: string;
    replay_step?: number;
    completed_at?: string | null;
  };
  shot_error?: string; // capture failed → show 「无截图」, never borrow
  point?: { x: number; y: number }; // tap point, device px
}
export interface Repeat {
  k: number;
  n: number;
}
export interface CaseLayers {
  execution: string; // ok | exit_<code> | interrupted
  script_checks: string; // executed_review_required | failed | interrupted
  agent_observation: string; // completed | replay_divergence | failed
  business: string; // review_required | failed | interrupted
  independent_review: unknown | null;
  human_confirmation: boolean;
  green_from_process_zero: boolean;
  green_from_sdk_completed: boolean;
}
export interface RunCase {
  name: string;
  ref: string;
  status: CaseStatus;
  raw_status: string;
  message: string;
  duration?: number | null;
  layers?: CaseLayers;
}
export interface EvidenceLink {
  kind: string;
  name: string;
  href: string;
}
export interface RunTask {
  id: string; // e.g. edge:<edge>@android#agent | l1-desktop
  label: string;
  edge?: string;
  edge_id: string;
  platform?: Platform | "";
  repeat?: Repeat;
  state: RunState;
  exit_code: number | null;
  duration_s: number | null;
  note?: string;
  steps?: Step[];
  cases: RunCase[];
  cmd: string[];
  device?: { id: string; name: string; kind: DeviceKind };
  device_request: string;
  needs_device: Platform | null | string;
  runnable_refs: string[];
  skipped_refs: unknown[];
  evidence_dir?: string;
  recordings?: unknown[];
  layers?: CaseLayers;
  human_confirmation?: boolean;
  independent_review?: unknown | null;
  // runner internals present in the file, not needed by the UI:
  builder: string;
  setup: string;
  env: Record<string, string>;
  parse_xml: boolean;
  only_testing: string[];
  xml_dir: string | null;
  xml_base: string | null;
  artemis_output: string | null;
  agent_device_output: string;
  agent_platform: string;
}
export interface PackageInfo {
  platform: Platform;
  package: string;
  version: string;
  version_code: number;
  sha256: string;
  artifact: string;
}
export interface RunRecord {
  id: string; // YYYYMMDDTHHMMSS-<sha8>
  started: string;
  finished: string | null;
  state: RunState;
  source?: RunSource;
  git: GitInfo;
  selection: string[];
  device: string;
  pid?: number;
  log: string;
  tasks: RunTask[];
  pass_count: number;
  fail_count: number;
  skip_count?: number;
  uncovered_count?: number;
  blocked_count?: number;
  duration_s?: number | null;
  historical?: boolean;
  note?: string;
  packages?: Partial<Record<Platform, PackageInfo>>;
  product_version?: { name: string; code: number };
}

// ---------- GET /api/runs/<id> (enrich_run) ----------
export interface RunDetail extends RunRecord {
  witnesses: { file: string; mtime: number }[];
  tasks: (RunTask & {
    counts: { total: number; passed: number; failed: number; skipped: number };
    map_refs?: {
      ref: string;
      status: CaseStatus;
      duration?: number | null;
      message?: string;
    }[];
    evidence_links?: EvidenceLink[];
  })[];
  evidence_links?: EvidenceLink[];
}

// ---------- GET /api/runs ----------
export interface RunSummary {
  id: string;
  started: string;
  finished: string | null;
  state: RunState;
  git: GitInfo;
  selection: string[];
  source: string; // "" when absent
  device: string;
  platforms: Platform[];
  pass_count: number;
  fail_count: number;
  uncovered_count: number;
  skip_count: number;
  duration_s: number | null;
  historical: boolean;
}
export interface RunsResponse {
  runs: RunSummary[];
} // newest start first

// ---------- GET /api/status[?id=] (project_status) ----------
export interface Watch {
  platform: Platform;
  device: string;
  name: string;
  kind: DeviceKind;
}
export interface StatusTask {
  id: string;
  edge: string | null;
  edge_id: string | null;
  platform: Platform | "";
  repeat: Repeat;
  label: string;
  state: RunState;
  exit_code: number | null;
  duration_s: number | null;
  note: string;
  steps: Step[];
}
export interface StatusResponse {
  active: boolean; // state in running|paused
  state: StatusState;
  id: string | null;
  source: RunSource | null;
  pid: number | null;
  git?: GitInfo; // absent when idle
  packages?: Partial<Record<Platform, PackageInfo>>;
  package_refusal?: string;
  queue: StatusTask[];
  tasks: StatusTask[]; // same array as queue
  progress: { done: number; total: number; failed: number };
  pass_count?: number;
  fail_count?: number;
  uncovered_count?: number;
  current: { id: string; edge_id: string | null; elapsed_s: number } | null;
  elapsed_s: number;
  log_tail: string[];
  pause_queue: boolean;
  semantics: string;
  live: {
    preview: { exists: boolean; mtime_ms: number; tag: string; test: string };
    keyframes: { file: string; [k: string]: unknown }[]; // last 24; /artifacts/keyframes/<file>
  };
  watch: Watch | null; // = watches.android
  watches: Partial<Record<Platform, Watch>>;
  steps: Step[]; // steps for the watched task
}

// ---------- GET /api/tasks ----------
export interface TasksResponse {
  runnable: {
    id: string;
    label: string;
    cmd: string[];
    heavy: boolean;
    platforms: Platform[];
    needs_device: string | null;
  }[];
  manual: unknown[]; // MANUAL_TASKS = [] today
  semantics: string;
  edge: { pattern: string; label: string };
}

// ---------- GET /api/devices (5 s server cache; never boots) ----------
export type DeviceKind = "emulator" | "physical" | "simulator" | "host";
export type DeviceState =
  | "ready"
  | "shutdown"
  | "offline"
  | "unavailable"
  | (string & {});
export interface Device {
  id: string;
  name: string;
  platform: Platform;
  kind: DeviceKind;
  state: DeviceState;
  bootable?: boolean;
  avd?: string;
  runtime?: string;
}
export type DevicesResponse = Record<Platform, Device[]>;

// ---------- device media (non-JSON) ----------
// GET /api/device-video?platform=&device=&n=<ts>   200 application/octet-stream, HTTP chunked.
//     Body = packets of [u32 big-endian length][payload]. payload[0]==0x7b ('{') → JSON config
//     { codec?: string, ... } (default "avc1.42E01E"); else an Annex-B H.264 access unit.
//     Android + iOS Simulator only; 204 when no live run / desktop / physical iOS.
// GET /api/device-frame?platform=&device=[&idle=1]  200 image/png | 204
//     no live run: 204 unless idle=1, then ≤1 frame / 2 s per device; live run: ≤1 fps
// GET /api/device-stream?platform=&device=  multipart/x-mixed-replace PNG (MJPEG-style) | 204
// GET /api/scrcpy?platform=android&device=  { ok, ... } | 409 { ok:false, error }

// ---------- GET /api/artemis/history (historical, deprecated) ----------
export interface ArtemisHistoryResponse {
  run: RunRecord & { historical: true };
  color_audit: {
    edge_id: string;
    note: string;
    rewritten: boolean;
    round2_result: string;
    round1_result: string | null;
    original_round2_status: string | null;
    file_recheck: unknown | null;
  };
  human_confirmation: string;
  add_more: unknown | null;
}

// ---------- writes (page-only) ----------
export interface RunRequest {
  tasks: string[];
  device?: string;
  repeat?: number;
  source?: RunSource;
}
export interface RunStarted {
  id: string;
  state: RunState;
  selection: string[];
} // 202; 409 {error,id} when busy
export type StopResponse = Record<string, unknown>;
/** POST /api/confirm — ONLY from a human click in the page. Token from
 *  <meta name="ewm-confirm-token">, body.token or X-EWM-Confirm-Token. 403 without it. */
export interface ConfirmRequest {
  token: string;
  edge_id: string;
  run_id: string;
}
export interface ConfirmRevokeRequest {
  token: string;
  edge_id: string;
  revoke: true;
} // or DELETE {token, edge_id}
export interface ConfirmResponse {
  edge_id: string;
  run_id: string;
  confirmed_at: string;
  actor: "human";
}
/** 403: token missing/stale → prompt reload, no retry. 400: validation (unknown edge/run,
 *  run does not cover edge, result not passed/review_required once ROS-7 lands) → show verbatim. */
export interface ConfirmError {
  error: string;
}
