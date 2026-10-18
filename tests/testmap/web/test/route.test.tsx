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

const text = "edge:editor-to-text-edit-sheet@android#agent";
const style = "edge:editor-typeface-style@android#agent";
vi.mock("../src/Catalog", () => ({
  CatalogView: ({ toggle }: { toggle: (id: string) => void }) => (
    <>
      <button onClick={() => toggle(style)}>Add style</button>
      <button onClick={() => toggle(text)}>Add text</button>
    </>
  ),
}));
vi.mock("../src/Run", () => ({ RunView: () => <p>Run view</p> }));
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
});

it("previews continuous routes without starting, rejects stale or unsupported plans, and submits the chosen order", async () => {
  localStorage.setItem("testmap-language", "en");
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
  let unavailable = true;
  let resolvePlan: ((response: Response) => void) | undefined;
  const response = (body: unknown) => new Response(JSON.stringify(body));
  const plan = {
    supported: true,
    platform: "android",
    setup: "editor",
    segments: [
      {
        task_id: text,
        edge_id: "editor-to-text-edit-sheet",
        entry: "editor",
        exit: "editor",
        steps: 3,
      },
      {
        task_id: style,
        edge_id: "editor-typeface-style",
        entry: "editor",
        exit: "editor-style",
        steps: 4,
      },
    ],
  };
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === "/api/catalog")
      return response({ edges: [], copy: {}, agent: {}, nodes: [] });
    if (path === "/api/map")
      return response({ head: { sha: "abcdef0123", dirty: false } });
    if (path === "/api/tasks") return response({ runnable: [] });
    if (path === "/api/devices")
      return response({
        android: [
          {
            id: "device-1",
            name: "Android test",
            platform: "android",
            kind: "emulator",
            state: "ready",
          },
        ],
      });
    if (path === "/api/route-plan") {
      if (unavailable)
        return new Response(
          JSON.stringify({ error: "Route preview unavailable" }),
          { status: 404 },
        );
      const body = JSON.parse(String(init?.body));
      if (body.tasks[0] !== text)
        return response({
          supported: false,
          reason: "Typeface must be the final segment",
          segments: [],
        });
      if (body.device === "device-1")
        return new Promise<Response>((resolve) => {
          resolvePlan = resolve;
        });
      return response(plan);
    }
    if (path === "/api/run")
      return response({ id: "route-run", state: "running" });
    throw new Error(`Unexpected request ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  render(<App />);
  fireEvent.click(await screen.findByRole("button", { name: "Add style" }));
  fireEvent.click(screen.getByRole("button", { name: "Add text" }));
  fireEvent.click(
    screen.getByRole("button", { name: "Run settings & host checks" }),
  );
  const mode = screen.getByRole("combobox", { name: "Execution mode" });
  expect((mode as HTMLSelectElement).value).toBe("independent");
  fireEvent.change(mode, { target: { value: "route" } });
  await screen.findByText("Route preview unavailable");
  const start = screen.getByRole("button", {
    name: "Start run",
  }) as HTMLButtonElement;
  expect(start.disabled).toBe(true);
  expect(
    fetchMock.mock.calls.filter(([path]) => path === "/api/run"),
  ).toHaveLength(0);
  unavailable = false;
  fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "2" } });
  fireEvent.click(
    screen.getByRole("button", {
      name: `Move up ${text.slice(5).replace("@", " · ")}`,
    }),
  );
  await screen.findByText("Route ready");
  expect(start.disabled).toBe(false);
  fireEvent.click(
    screen.getByRole("button", {
      name: `Move down ${text.slice(5).replace("@", " · ")}`,
    }),
  );
  await screen.findByText("Typeface must be the final segment");
  expect(start.disabled).toBe(true);
  fireEvent.click(
    screen.getByRole("button", {
      name: `Move up ${text.slice(5).replace("@", " · ")}`,
    }),
  );
  await screen.findByText("Route ready");
  fireEvent.change(screen.getByRole("combobox", { name: "Device" }), {
    target: { value: "device-1" },
  });
  expect(start.disabled).toBe(true);
  await waitFor(() => expect(resolvePlan).toBeDefined());
  resolvePlan!(response(plan));
  await waitFor(() => expect(start.disabled).toBe(false));
  fireEvent.click(start);
  await screen.findByText("Run view");
  const runCalls = fetchMock.mock.calls.filter(([path]) => path === "/api/run");
  expect(runCalls).toHaveLength(1);
  expect(JSON.parse(String(runCalls[0][1]?.body))).toEqual({
    tasks: [text, style],
    device: "device-1",
    repeat: 2,
    source: "manual",
    execution_mode: "route",
  });
});
