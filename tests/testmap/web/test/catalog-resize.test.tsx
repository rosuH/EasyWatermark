// @vitest-environment jsdom
import { act, cleanup, render, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { Node } from "@xyflow/react";
const state = vi.hoisted(() => ({
  width: 1004,
  height: 682,
  ready: true,
  fit: vi.fn().mockResolvedValue(true),
}));
vi.mock("@xyflow/react", async (original) => ({
  ...(await original<typeof import("@xyflow/react")>()),
  useReactFlow: () => ({ fitView: state.fit }),
  useNodesInitialized: () => state.ready,
  useStore: (select: (value: typeof state) => unknown) => select(state),
}));
import { FitOnResize, useCatalogNodes } from "../src/Catalog";
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  state.fit.mockClear();
  state.ready = true;
});
function frames() {
  vi.useFakeTimers();
  vi.stubGlobal("requestAnimationFrame", (callback: () => void) =>
    setTimeout(callback, 16),
  );
  vi.stubGlobal("cancelAnimationFrame", clearTimeout);
}
it("fits the final dimensions after consecutive resizes without a fixed delay", () => {
  frames();
  state.width = 1004;
  state.height = 682;
  const { rerender } = render(<FitOnResize />);
  state.width = 852;
  state.height = 582;
  rerender(<FitOnResize />);
  state.width = 674;
  rerender(<FitOnResize />);
  act(() => {
    vi.advanceTimersByTime(16);
  });
  expect(state.fit).toHaveBeenCalledOnce();
  expect(state.fit).toHaveBeenCalledWith({
    duration: 0,
    padding: { top: "28%", bottom: "8%", x: "6%" },
  });
  // Focus/selection remeasurement must not reset the user's manual pan/zoom.
  state.ready = false;
  rerender(<FitOnResize />);
  state.ready = true;
  rerender(<FitOnResize />);
  act(() => {
    vi.advanceTimersByTime(16);
  });
  expect(state.fit).toHaveBeenCalledOnce();
});
it("cancels fitting when leaving the map", () => {
  frames();
  const { unmount } = render(<FitOnResize />);
  unmount();
  act(() => {
    vi.runAllTimers();
  });
  expect(state.fit).not.toHaveBeenCalled();
});
it("preserves React Flow measurements when focus or translated labels change", () => {
  const initial: Node[] = [
    { id: "entry", position: { x: 0, y: 0 }, data: { label: "Entry" } },
  ];
  const { result, rerender } = renderHook(
    ({ definitions }) => useCatalogNodes(definitions),
    { initialProps: { definitions: initial } },
  );
  act(() =>
    result.current.onNodesChange([
      {
        id: "entry",
        type: "dimensions",
        dimensions: { width: 190, height: 72 },
      },
    ]),
  );
  expect(result.current.nodes[0].measured).toEqual({ width: 190, height: 72 });
  rerender({
    definitions: [
      { ...initial[0], className: "focused", data: { label: "入口" } },
    ],
  });
  expect(result.current.nodes[0].measured).toEqual({ width: 190, height: 72 });
  expect(result.current.nodes[0].data.label).toBe("入口");
});
