// @vitest-environment jsdom
import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import App from "../src/App";

vi.mock("../src/Catalog", () => ({
  CatalogView: ({ toggle }: { toggle: (id: string) => void }) => (
    <button onClick={() => toggle("edge:pick-to-editor@android#agent")}>
      Add draft task
    </button>
  ),
}));
vi.mock("../src/Run", () => ({
  RunView: () => <p>Run view</p>,
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
});

function renderJourney(activeInitially: boolean) {
  localStorage.setItem("testmap-language", "en");
  let active = activeInitially;
  const runBodies: unknown[] = [];
  const response = (body: unknown) => new Response(JSON.stringify(body));
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === "/api/catalog")
      return response({ edges: [], copy: {}, agent: {}, nodes: [] });
    if (path === "/api/map")
      return response({ head: { sha: "abcdef0123", dirty: false } });
    if (path === "/api/tasks")
      return response({
        runnable: [
          {
            id: "android-ai-journey",
            label: "Android AI journey: pick, edit and export",
            builder: "e2e-runner",
            needs_device: "android",
            platforms: ["android"],
            cmd: [],
            heavy: false,
          },
          {
            id: "android-large-image-preview",
            label: "Android large image preview: diagnose Canvas crash",
            builder: "e2e-runner",
            needs_device: "android",
            platforms: ["android"],
            cmd: [],
            heavy: false,
          },
        ],
      });
    if (path === "/api/devices")
      return response({
        android: [
          {
            id: "adb-1",
            name: "Pixel 8",
            platform: "android",
            kind: "physical",
            state: "ready",
          },
          {
            id: "adb-2",
            name: "Offline phone",
            platform: "android",
            kind: "physical",
            state: "offline",
          },
        ],
        ios: [],
      });
    if (path === "/api/status")
      return response({
        active,
        id: active ? "existing-run" : null,
        state: active ? "running" : "idle",
        queue: [],
        tasks: [],
        progress: { done: 0, total: 0, failed: 0 },
        current: null,
        elapsed_s: 0,
        log_tail: [],
        pause_queue: false,
        semantics: "",
        live: {
          preview: { exists: false, mtime_ms: 0, tag: "", test: "" },
          keyframes: [],
        },
        watch: null,
        watches: {},
        steps: [],
      });
    if (path === "/api/run") {
      runBodies.push(JSON.parse(String(init?.body)));
      return response({
        id: "journey-run",
        state: "running",
        selection: ["android-ai-journey"],
      });
    }
    throw new Error("Unexpected request " + path);
  });
  vi.stubGlobal("fetch", fetchMock);
  render(<App />);
  return {
    fetchMock,
    runBodies,
    setActive(value: boolean) {
      active = value;
    },
  };
}

it("keeps the edit-and-export journey as default and starts only it on one ready device", async () => {
  const { fetchMock, runBodies, setActive } = renderJourney(true);
  const scenario = await screen.findByRole("combobox", {
    name: "Android scenario",
  });
  const run = await screen.findByRole("button", {
    name: "Run Android AI journey",
  });
  expect((scenario as HTMLSelectElement).value).toBe("android-ai-journey");
  expect(await screen.findByText(/A run is already active/)).toBeTruthy();
  expect((run as HTMLButtonElement).disabled).toBe(true);

  fireEvent.click(screen.getByRole("button", { name: "Add draft task" }));
  fireEvent.change(screen.getByRole("combobox", { name: "Android device" }), {
    target: { value: "adb-1" },
  });
  expect(screen.getByText("Bound to: Pixel 8 (adb-1) · ready")).toBeTruthy();
  setActive(false);
  fireEvent.click(screen.getByRole("button", { name: "Refresh devices" }));
  await waitFor(() => expect((run as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(run);

  await screen.findByText("Run view");
  expect(runBodies).toEqual([
    {
      tasks: ["android-ai-journey"],
      device: "adb-1",
      repeat: 1,
      execution_mode: "independent",
    },
  ]);
  expect(
    fetchMock.mock.calls.some(([path]) => path === "/api/route-plan"),
  ).toBe(false);
});

it("starts the large-image crash reproduction as its own independent task", async () => {
  const { fetchMock, runBodies, setActive } = renderJourney(true);
  const scenario = await screen.findByRole("combobox", {
    name: "Android scenario",
  });
  setActive(false);
  fireEvent.click(screen.getByRole("button", { name: "Refresh devices" }));
  await waitFor(() => expect((scenario as HTMLSelectElement).disabled).toBe(false));
  fireEvent.change(scenario, {
    target: { value: "android-large-image-preview" },
  });
  expect(
    screen.getByText(/49,861 × 1,350 photo in the editor/),
  ).toBeTruthy();
  const run = await screen.findByRole("button", {
    name: "Run large-image crash reproduction",
  });
  fireEvent.click(screen.getByRole("button", { name: "Add draft task" }));
  fireEvent.change(screen.getByRole("combobox", { name: "Android device" }), {
    target: { value: "adb-1" },
  });
  await waitFor(() => expect((run as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(run);

  await screen.findByText("Run view");
  expect(runBodies).toEqual([
    {
      tasks: ["android-large-image-preview"],
      device: "adb-1",
      repeat: 1,
      execution_mode: "independent",
    },
  ]);
  expect(
    fetchMock.mock.calls.some(([path]) => path === "/api/route-plan"),
  ).toBe(false);
});
