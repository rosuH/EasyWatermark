// @vitest-environment jsdom
import { afterEach, expect, it, vi } from "vitest";
import { concat, watchMedia } from "../src/media";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

function packet(kind: number) {
  const payload = new Uint8Array([0, 0, 0, 1, kind, 42]);
  const size = new Uint8Array(4);
  new DataView(size.buffer).setUint32(0, payload.length);
  return concat([size, payload]);
}

it.each([1, 5])(
  "falls back on decoder backlog before decoding NAL %s",
  async (kind) => {
    vi.useFakeTimers();
    const decode = vi.fn();
    const close = vi.fn();
    class Decoder {
      state = "configured";
      get decodeQueueSize() {
        return decode.mock.calls.length ? 6 : 0;
      }
      configure() {}
      decode = decode;
      close() {
        this.state = "closed";
        close();
      }
    }
    vi.stubGlobal("VideoDecoder", Decoder);
    vi.stubGlobal(
      "EncodedVideoChunk",
      class {
        constructor(public init: unknown) {}
      },
    );
    const read = vi.fn().mockResolvedValue({
      done: false,
      value: concat([packet(7), packet(8), packet(5), packet(kind), packet(1)]),
    });
    let videoSignal: AbortSignal | undefined;
    const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
      if (url.startsWith("/api/device-video")) {
        videoSignal = init.signal as AbortSignal;
        return { ok: true, status: 200, body: { getReader: () => ({ read }) } };
      }
      if (url.startsWith("/api/device-frame"))
        return { ok: false, status: 204 };
      throw new Error(`Unexpected mock request ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    const clearRect = vi.fn();
    const canvas = {
      getContext: () => ({ clearRect }),
      width: 100,
      height: 100,
    } as unknown as HTMLCanvasElement;
    const update = vi.fn();
    const diagnose = vi.fn();
    const stop = watchMedia(canvas, "android", "mock", update, diagnose);
    await vi.advanceTimersByTimeAsync(0);
    expect(decode).toHaveBeenCalledTimes(1);
    expect(diagnose).toHaveBeenCalledTimes(1);
    expect(diagnose.mock.calls[0][0]).toMatchObject({
      reason: "decoder-backlog",
      firstPacketMs: 0,
      firstIdrMs: 0,
      firstOutputMs: null,
      decodeCount: 1,
      outputCount: 0,
      maxQueue: 6,
    });
    expect(close).toHaveBeenCalledTimes(1);
    expect(videoSignal?.aborted).toBe(true);
    expect(read).toHaveBeenCalledTimes(1);
    expect(
      fetchMock.mock.calls.filter(([url]) =>
        url.startsWith("/api/device-frame"),
      ),
    ).toHaveLength(1);
    expect(update).not.toHaveBeenCalledWith("video");
    expect(update).toHaveBeenLastCalledWith("disconnected");
    stop();
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  },
);

it.each([false, true])(
  "keeps the original 2s deadline with first packet received=%s",
  async (hasPacket) => {
    vi.useFakeTimers();
    const decoder = vi.fn();
    vi.stubGlobal("VideoDecoder", decoder);
    const read = vi.fn().mockImplementation(() => new Promise(() => {}));
    if (hasPacket)
      read.mockResolvedValueOnce({ done: false, value: packet(7) });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) =>
        url.startsWith("/api/device-video")
          ? { ok: true, status: 200, body: { getReader: () => ({ read }) } }
          : { ok: false, status: 204 },
      ),
    );
    const canvas = {
      getContext: () => ({ clearRect: vi.fn() }),
      width: 0,
      height: 0,
    } as unknown as HTMLCanvasElement;
    const diagnose = vi.fn();
    const stop = watchMedia(canvas, "android", "mock", vi.fn(), diagnose);
    await vi.advanceTimersByTimeAsync(1999);
    expect(diagnose).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(diagnose).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        reason: "first-frame-timeout",
        elapsedMs: 2000,
        firstPacketMs: hasPacket ? 0 : null,
        firstIdrMs: null,
        firstOutputMs: null,
        decodeCount: 0,
        outputCount: 0,
        maxQueue: 0,
      }),
    );
    expect(decoder).not.toHaveBeenCalled();
    stop();
  },
);

it("records the decoder's real error once, before the fallback's fetch result", async () => {
  vi.useFakeTimers();
  class Decoder {
    state = "configured";
    decodeQueueSize = 0;
    constructor(private callbacks: { error: (error: DOMException) => void }) {}
    configure() {}
    decode() {
      queueMicrotask(() =>
        this.callbacks.error(
          new DOMException("fixture decode rejected", "EncodingError"),
        ),
      );
    }
    close() {
      this.state = "closed";
    }
  }
  vi.stubGlobal("VideoDecoder", Decoder);
  vi.stubGlobal(
    "EncodedVideoChunk",
    class {
      constructor(public init: unknown) {}
    },
  );
  const read = vi
    .fn()
    .mockResolvedValueOnce({
      done: false,
      value: concat([packet(7), packet(8), packet(5)]),
    })
    .mockImplementation(() => new Promise(() => {}));
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      url.startsWith("/api/device-video")
        ? { ok: true, status: 200, body: { getReader: () => ({ read }) } }
        : { ok: false, status: 204 },
    ),
  );
  const canvas = {
    getContext: () => ({ clearRect: vi.fn() }),
    width: 0,
    height: 0,
  } as unknown as HTMLCanvasElement;
  const diagnose = vi.fn();
  const stop = watchMedia(canvas, "android", "mock", vi.fn(), diagnose);
  await vi.advanceTimersByTimeAsync(0);
  expect(diagnose).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      reason: "decoder-error",
      errorName: "EncodingError",
      errorMessage: "fixture decode rejected",
      decodeCount: 1,
      outputCount: 0,
      firstIdrMs: 0,
      firstOutputMs: null,
    }),
  );
  await vi.advanceTimersByTimeAsync(2500);
  expect(diagnose).toHaveBeenCalledTimes(1);
  stop();
});
