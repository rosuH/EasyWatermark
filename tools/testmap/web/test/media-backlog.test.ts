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
        return {
          ok: true,
          status: 200,
          body: {
            getReader: () => ({ read, cancel: vi.fn(), releaseLock: vi.fn() }),
          },
        };
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
        ? {
            ok: true,
            status: 200,
            body: {
              getReader: () => ({
                read,
                cancel: vi.fn(),
                releaseLock: vi.fn(),
              }),
            },
          }
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

// Real stream semantics, controlled time and decoder callbacks; no device access.
function streamFixture(connect = true) {
  vi.useFakeTimers();
  let input!: ReadableStreamDefaultController<Uint8Array>;
  let output!: (frame: VideoFrame) => void;
  const cancel = vi.fn();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      input = controller;
    },
    cancel,
  });
  const decode = vi.fn();
  const close = vi.fn();
  class Decoder {
    state = "configured";
    decodeQueueSize = 0;
    constructor(callbacks: { output: (frame: VideoFrame) => void }) {
      output = callbacks.output;
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
  let signal!: AbortSignal;
  const fetchMock = vi.fn((url: string, init: RequestInit) => {
    if (url.startsWith("/api/device-frame"))
      return Promise.resolve({ ok: false, status: 204 });
    signal = init.signal as AbortSignal;
    signal.addEventListener("abort", () =>
      input.error(new DOMException("aborted", "AbortError")),
    );
    return connect
      ? Promise.resolve({ ok: true, status: 200, body })
      : new Promise(() => {});
  });
  vi.stubGlobal("fetch", fetchMock);
  const canvas = {
    width: 0,
    height: 0,
    style: {},
    getContext: () => ({ clearRect: vi.fn(), drawImage: vi.fn() }),
  } as unknown as HTMLCanvasElement;
  const update = vi.fn(),
    diagnose = vi.fn();
  const stop = watchMedia(canvas, "android", "mock", update, diagnose);
  const config = () => {
    const payload = new TextEncoder().encode('{"codec":"avc1.42C032"}');
    const size = new Uint8Array(4);
    new DataView(size.buffer).setUint32(0, payload.length);
    input.enqueue(concat([size, payload]));
  };
  return {
    input,
    body,
    cancel,
    decode,
    close,
    update,
    diagnose,
    stop,
    fetchMock,
    config,
    signal: () => signal,
    output: () => {
      const frame = { displayWidth: 10, displayHeight: 20, close: vi.fn() };
      output(frame as unknown as VideoFrame);
      return frame;
    },
  };
}

it("waits for complete parameters despite early IDRs, accepts cold-start timing, then detects stalled output", async () => {
  const f = streamFixture();
  await vi.advanceTimersByTimeAsync(520);
  f.config();
  await vi.advanceTimersByTimeAsync(1380);
  f.input.enqueue(packet(5));
  await vi.advanceTimersByTimeAsync(0);
  expect(f.decode).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(25);
  f.input.enqueue(concat([packet(7), packet(5)]));
  await vi.advanceTimersByTimeAsync(0);
  expect(f.decode).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(5);
  f.input.enqueue(packet(8));
  await vi.advanceTimersByTimeAsync(95);
  f.input.enqueue(packet(5));
  await vi.advanceTimersByTimeAsync(25);
  expect(f.diagnose).not.toHaveBeenCalled();
  expect(f.decode).toHaveBeenCalledExactlyOnceWith({
    init: {
      type: "key",
      timestamp: 0,
      data: concat([
        packet(7).slice(4),
        packet(8).slice(4),
        packet(5).slice(4),
      ]),
    },
  });
  expect(f.output().close).toHaveBeenCalledOnce();
  expect(f.update).toHaveBeenLastCalledWith("video");
  await vi.advanceTimersByTimeAsync(1200);
  expect(f.diagnose).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      reason: "frame-timeout",
      responseMs: 0,
      firstPacketMs: 520,
      firstNalMs: 1900,
      parametersReadyMs: 1930,
      firstIdrMs: 1900,
      firstDecodeMs: 2025,
      firstOutputMs: 2050,
      elapsedMs: 3250,
    }),
  );
  expect(f.signal().aborted).toBe(true);
  expect(f.body.locked).toBe(false);
  f.stop();
  const count = f.fetchMock.mock.calls.length;
  await vi.advanceTimersByTimeAsync(20000);
  expect(f.fetchMock).toHaveBeenCalledTimes(count);
});

it.each(["connection", "startup", "keyframe", "first-frame"] as const)(
  "bounds the %s phase without letting irrelevant packets extend it",
  async (stage) => {
    const f = streamFixture(stage !== "connection");
    await vi.advanceTimersByTimeAsync(0);
    if (stage === "keyframe" || stage === "first-frame") {
      f.input.enqueue(
        concat([
          packet(7),
          packet(8),
          ...(stage === "first-frame" ? [packet(5)] : []),
        ]),
      );
      await vi.advanceTimersByTimeAsync(0);
    }
    const budget =
      stage === "connection" ? 10000 : stage === "startup" ? 12000 : 2000;
    for (let n = 0; n < budget / 500 - 1; n++) {
      await vi.advanceTimersByTimeAsync(500);
      if (stage !== "connection") f.config();
      if (stage === "keyframe" || stage === "first-frame")
        f.input.enqueue(concat([packet(6), packet(9), packet(1)]));
    }
    await vi.advanceTimersByTimeAsync(499);
    expect(f.diagnose).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(f.diagnose).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        reason: `${stage}-timeout`,
        elapsedMs: budget,
        firstOutputMs: null,
      }),
    );
    expect(f.decode).toHaveBeenCalledTimes(stage === "first-frame" ? 4 : 0);
    expect(f.signal().aborted).toBe(true);
    if (stage !== "connection") expect(f.body.locked).toBe(false);
    f.stop();
  },
);

it("cancels a pending startup read without fallback or later polling", async () => {
  const f = streamFixture();
  await vi.advanceTimersByTimeAsync(1000);
  f.config();
  await vi.advanceTimersByTimeAsync(0);
  f.stop();
  await vi.advanceTimersByTimeAsync(30000);
  expect(f.signal().aborted).toBe(true);
  expect(f.body.locked).toBe(false);
  expect(f.diagnose).not.toHaveBeenCalled();
  expect(f.fetchMock).toHaveBeenCalledTimes(1);
  expect(f.decode).not.toHaveBeenCalled();
});
