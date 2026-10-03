// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { Confirm } from "../src/Confirm";
import { DeviceLane } from "../src/Run";
import { useResource } from "../src/api";
import { canConfirm, matches, emptyFilters, type Catalog } from "../src/model";
import { concat, packets, watchMedia } from "../src/media";
import type {
  MapEdge,
  MapResponse,
  RunDetail,
  StatusTask,
} from "../src/api-types";
const edge = {
  id: "pick",
  from: "entry",
  to: "editor",
  cases: [{ ref: "Case.pick", layer: "L1" }],
  platforms: {
    android: { drive: "seam", via: "picker" },
    ios: { drive: "real", via: "native" },
  },
  owners: [],
  priority: "core",
  trigger: { kind: "tag", value: "pick" },
} as MapEdge;
const map = {
  nodes: [],
  edges: [edge],
  results: { pick: "review_required" },
  result_runs: { pick: "run-new" },
  confirmations: {},
  witnesses: [],
  badges: {},
  latest_run: "run-new",
  head: { sha: "abcdef012345", dirty: false },
} as MapResponse;
const record = {
  id: "run-new",
  state: "review_required",
  started: "2026-10-02",
  finished: "2026-10-02",
  git: map.head,
  tasks: [
    {
      id: "edge:pick@android#agent",
      edge: "pick",
      platform: "android",
      state: "review_required",
      duration_s: 10,
      steps: [],
      cases: [],
    },
  ],
} as unknown as RunDetail;
const fetchMock = vi.fn();
beforeEach(() => {
  document.head.innerHTML =
    '<meta name="ewm-confirm-token" content="mock-token">';
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "visible",
  });
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockImplementation(async (path: string) => {
    if (path.startsWith("/api/runs/"))
      return new Response(JSON.stringify(record));
    if (path === "/api/confirm") return new Response("{}");
    throw new Error(`Unmocked request ${path}`);
  });
});
afterEach(() => {
  cleanup();
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const confirmCalls = () =>
  fetchMock.mock.calls
    .filter(([path]) => path === "/api/confirm")
    .map(([, init]) => JSON.parse(init.body));
describe("Human confirmation uses mock transport only", () => {
  it("does not write on render, binds the displayed run, and undoes to the previous run", async () => {
    const previous = {
      edge_id: "pick",
      run_id: "run-old",
      confirmed_at: "yesterday",
      actor: "human",
      stale: true,
      latest_run: "run-new",
    };
    render(
      <Confirm
        edge={edge}
        map={{ ...map, confirmations: { pick: previous } }}
        refresh={() => {}}
        openRun={() => {}}
      />,
    );
    const button = await screen.findByRole("button", {
      name: "Confirm run-new",
    });
    await waitFor(() =>
      expect((button as HTMLButtonElement).disabled).toBe(false),
    );
    expect(confirmCalls()).toEqual([]);
    fireEvent.click(button);
    await screen.findByRole("button", { name: "Undo" });
    expect(confirmCalls()[0]).toEqual({
      edge_id: "pick",
      run_id: "run-new",
      token: "mock-token",
    });
    fireEvent.click(screen.getByRole("button", { name: "Undo" }));
    await waitFor(() =>
      expect(confirmCalls()[1]).toEqual({
        edge_id: "pick",
        run_id: "run-old",
        token: "mock-token",
      }),
    );
  });
  it("requires two explicit clicks to revoke", async () => {
    render(
      <Confirm
        edge={edge}
        map={{
          ...map,
          confirmations: {
            pick: {
              edge_id: "pick",
              run_id: "run-new",
              confirmed_at: "today",
              actor: "human",
              stale: false,
              latest_run: "run-new",
            },
          },
        }}
        refresh={() => {}}
        openRun={() => {}}
      />,
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Revoke confirmation" }),
    );
    expect(confirmCalls()).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: "Confirm revocation" }));
    await waitFor(() =>
      expect(confirmCalls()).toEqual([
        { edge_id: "pick", revoke: true, token: "mock-token" },
      ]),
    );
  });
  it("rejects failed edge results and shows 403 refresh guidance without retry", async () => {
    expect(
      canConfirm(
        { ...record, tasks: [{ ...record.tasks[0], state: "failed" }] },
        "pick",
      ),
    ).toBe(false);
    expect(
      canConfirm(
        {
          ...record,
          state: "failed",
          tasks: [
            record.tasks[0],
            { ...record.tasks[0], edge: "another-path", state: "failed" },
          ],
        },
        "pick",
      ),
    ).toBe(true);
    fetchMock.mockImplementation(async (path: string) =>
      path === "/api/confirm"
        ? new Response(JSON.stringify({ error: "expired token" }), {
            status: 403,
          })
        : new Response(JSON.stringify(record)),
    );
    render(
      <Confirm edge={edge} map={map} refresh={() => {}} openRun={() => {}} />,
    );
    const button = await screen.findByRole("button", {
      name: "Confirm run-new",
    });
    await waitFor(() =>
      expect((button as HTMLButtonElement).disabled).toBe(false),
    );
    fireEvent.click(button);
    await screen.findByText(/Refresh the page, then try again/);
    expect(confirmCalls()).toHaveLength(1);
  });
});
it("keeps clicked historical evidence pinned when live steps advance", async () => {
  const task = {
    id: "edge:pick@android",
    platform: "android",
    label: "Pick",
    repeat: { k: 1, n: 1 },
    state: "running",
    steps: [
      {
        n: 1,
        text: "First step",
        command: "tap",
        args: "first",
        state: "done",
        platform: "android",
        shot: "first.png",
      },
      {
        n: 2,
        text: "Second step",
        command: "tap",
        args: "second",
        state: "current",
        platform: "android",
      },
    ],
  } as StatusTask;
  const { rerender } = render(
    <DeviceLane platform="android" runId="old-run" task={task} live={false} />,
  );
  fireEvent.click(screen.getByRole("button", { name: /First step/ }));
  rerender(
    <DeviceLane
      platform="android"
      runId="old-run"
      task={{
        ...task,
        steps: [
          ...task.steps,
          {
            n: 3,
            text: "Third step",
            command: "tap",
            args: "third",
            state: "current",
            platform: "android",
            shot: "third.png",
          },
        ],
      }}
      live={true}
    />,
  );
  expect(screen.getByRole("img").getAttribute("src")).toBe(
    "/api/runs/old-run/steps/first.png",
  );
  expect(fetchMock.mock.calls).toHaveLength(0);
  expect(screen.getByText("Screenshot timing unverified")).toBeTruthy();
});
it("shows current-run successful evidence and labels its timing without confirming", () => {
  const proof = {
    ...record,
    tasks: [
      {
        ...record.tasks[0],
        steps: [
          {
            n: 1,
            text: "Latest success",
            platform: "android",
            state: "done",
            command: "press",
            args: "",
            shot: "proof.png",
            shot_capture: {
              method: "agent-device-inline",
              timing: "after-step-before-next-action",
            },
          },
        ],
      },
    ],
  } as RunDetail;
  render(
    <Confirm
      edge={edge}
      map={map}
      refresh={vi.fn()}
      openRun={vi.fn()}
      runResult={{ data: proof }}
    />,
  );
  const thumbnail = screen.getByRole("img");
  expect(thumbnail.getAttribute("src")).toBe(
    "/api/runs/run-new/steps/proof.png",
  );
  expect(thumbnail.getAttribute("alt")).toContain("After step");
  expect(confirmCalls()).toHaveLength(0);
});
it("renders screenshot capture errors instead of borrowing another step", () => {
  const task = {
    id: "t",
    platform: "ios",
    state: "failed",
    label: "Case",
    steps: [
      {
        n: 1,
        text: "Failed step",
        command: "tap",
        args: "target",
        state: "failed",
        platform: "ios",
        shot_error: "Capture timed out",
      },
    ],
  } as StatusTask;
  render(<DeviceLane platform="ios" runId="old" task={task} live={false} />);
  expect(screen.queryByRole("img")).toBeNull();
  expect(screen.getAllByText("Capture timed out").length).toBeGreaterThan(0);
});
it("aborts polling when hidden and ignores a stale response after run switches", async () => {
  const requests: {
    url: string;
    signal: AbortSignal;
    resolve: (response: Response) => void;
  }[] = [];
  fetchMock.mockImplementation(
    (url: string, init: RequestInit) =>
      new Promise((resolve) =>
        requests.push({ url, signal: init.signal!, resolve }),
      ),
  );
  function Probe({ url }: { url: string }) {
    const result = useResource<{ id: string }>(url, 1000);
    return <div>{result.data?.id || "loading"}</div>;
  }
  const { rerender } = render(<Probe url="/api/status?id=old" />);
  rerender(<Probe url="/api/status?id=new" />);
  expect(requests[0].signal.aborted).toBe(true);
  await act(async () => {
    requests[1].resolve(new Response('{"id":"new"}'));
  });
  expect(screen.getByText("new")).toBeTruthy();
  await act(async () => {
    requests[0].resolve(new Response('{"id":"old"}'));
  });
  expect(screen.queryByText("old")).toBeNull();
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "hidden",
  });
  fireEvent(document, new Event("visibilitychange"));
  expect(requests[1].signal.aborted).toBe(true);
});
it("parses split H264 framing and rejects oversized packets", () => {
  const payload = new Uint8Array([0, 0, 0, 1, 0x65, 42]);
  const length = new Uint8Array(4);
  new DataView(length.buffer).setUint32(0, payload.length);
  const bytes = concat([length, payload]);
  expect(packets(bytes.slice(0, 6)).payloads).toHaveLength(0);
  expect(packets(bytes).payloads[0]).toEqual(payload);
  expect(() => packets(new Uint8Array([255, 255, 255, 255]))).toThrow(
    "Invalid video packet",
  );
});
it("shares filtering semantics across map and tree, including platform and layer", () => {
  const catalog = {
    copy: { edges: { pick: { en: "Pick a photo" } }, cases: {}, nodes: {} },
  } as unknown as Catalog;
  expect(
    matches(
      edge,
      { ...emptyFilters, q: "photo", platform: "ios", layer: "L1" },
      catalog,
    ),
  ).toBe(true);
  expect(
    matches(edge, { ...emptyFilters, platform: "ios", drive: "seam" }, catalog),
  ).toBe(false);
});

it("opens path confirmation safely when the repository has no runs", () => {
  render(
    <Confirm
      edge={edge}
      map={{
        ...map,
        result_runs: {},
        results: {},
        confirmations: {},
        latest_run: null,
      }}
      refresh={() => {}}
      openRun={() => {}}
    />,
  );
  expect(screen.getByText(/No run covers this path yet/)).toBeTruthy();
  expect(confirmCalls()).toEqual([]);
});

it("expires the confirmation undo action after five seconds", async () => {
  render(
    <Confirm edge={edge} map={map} refresh={() => {}} openRun={() => {}} />,
  );
  const button = await screen.findByRole("button", { name: "Confirm run-new" });
  await waitFor(() =>
    expect((button as HTMLButtonElement).disabled).toBe(false),
  );
  vi.useFakeTimers();
  await act(async () => {
    fireEvent.click(button);
  });
  expect(screen.getByRole("button", { name: "Undo" })).toBeTruthy();
  await act(async () => {
    await vi.advanceTimersByTimeAsync(5000);
  });
  expect(screen.queryByRole("button", { name: "Undo" })).toBeNull();
});
it("falls back to still frames and cancels all retries on media cleanup", async () => {
  vi.useFakeTimers();
  vi.stubGlobal("VideoDecoder", undefined);
  const clearRect = vi.fn();
  const canvas = document.createElement("canvas");
  vi.spyOn(canvas, "getContext").mockReturnValue({
    clearRect,
  } as unknown as CanvasRenderingContext2D);
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  const states: string[] = [];
  const stop = watchMedia(canvas, "ios", "mock-device", (state) =>
    states.push(state),
  );
  await vi.advanceTimersByTimeAsync(0);
  expect(fetchMock.mock.calls[0][0]).toContain("/api/device-frame?");
  expect(states.at(-1)).toBe("disconnected");
  stop();
  const count = fetchMock.mock.calls.length;
  expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
  await vi.advanceTimersByTimeAsync(5000);
  expect(fetchMock.mock.calls).toHaveLength(count);
  expect(clearRect).toHaveBeenCalled();
});
