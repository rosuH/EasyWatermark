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
function streamFixture(connect = true, queued = false) {
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
  let instance!: Decoder;
  class Decoder extends EventTarget {
    state = "configured";
    decodeQueueSize = 0;
    constructor(callbacks: { output: (frame: VideoFrame) => void }) {
      super();
      instance = this;
      output = callbacks.output;
      if (queued)
        decode.mockImplementation(() => {
          this.decodeQueueSize++;
        });
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
    canvas,
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
    decoder: () => instance,
    drain: () => {
      instance.decodeQueueSize = 0;
      instance.dispatchEvent(new Event("dequeue"));
    },
    output: () => {
      const frame = { displayWidth: 10, displayHeight: 20, close: vi.fn() };
      output(frame as unknown as VideoFrame);
      return frame;
    },
  };
}

it("waits for complete parameters despite early IDRs, keeps a drained stream idle, then bounds new pending output", async () => {
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
  await vi.advanceTimersByTimeAsync(13000);
  expect(f.diagnose).not.toHaveBeenCalled();
  expect(f.update).toHaveBeenLastCalledWith("video");
  expect(f.signal().aborted).toBe(false);
  expect(f.body.locked).toBe(true);
  expect(f.close).not.toHaveBeenCalled();
  expect([f.canvas.width, f.canvas.height]).toEqual([10, 20]);
  f.input.enqueue(packet(1));
  await vi.advanceTimersByTimeAsync(1199);
  expect(f.decode).toHaveBeenCalledTimes(2);
  expect(f.diagnose).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
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
      elapsedMs: 16250,
      decodeCount: 2,
      outputCount: 1,
    }),
  );
  expect(f.signal().aborted).toBe(true);
  expect(f.body.locked).toBe(false);
  expect(f.close).toHaveBeenCalledOnce();
  f.stop();
  const count = f.fetchMock.mock.calls.length;
  await vi.advanceTimersByTimeAsync(20000);
  expect(f.fetchMock).toHaveBeenCalledTimes(count);
});

it("bounds multiple pending outputs from actual progress, not each new submission", async () => {
  const f = streamFixture();
  await vi.advanceTimersByTimeAsync(0);
  f.input.enqueue(concat([packet(7), packet(8), packet(5)]));
  await vi.advanceTimersByTimeAsync(0);
  f.output();
  f.input.enqueue(concat([packet(1), packet(1)]));
  await vi.advanceTimersByTimeAsync(600);
  f.output(); // One output is still pending; this is real progress.
  await vi.advanceTimersByTimeAsync(599);
  f.input.enqueue(packet(1)); // More input must not postpone the stall deadline.
  await vi.advanceTimersByTimeAsync(600);
  expect(f.diagnose).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
  expect(f.diagnose).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({
      reason: "frame-timeout",
      decodeCount: 4,
      outputCount: 2,
      elapsedMs: 1800,
    }),
  );
  expect(f.signal().aborted).toBe(true);
  expect(f.body.locked).toBe(false);
  expect(f.close).toHaveBeenCalledOnce();
  f.stop();
});

it("accounts for a synchronous decoder output before deciding whether work is pending", async () => {
  const f = streamFixture();
  f.decode.mockImplementation(() => f.output());
  await vi.advanceTimersByTimeAsync(0);
  f.input.enqueue(concat([packet(7), packet(8), packet(5), packet(1)]));
  await vi.advanceTimersByTimeAsync(13000);
  expect(f.decode).toHaveBeenCalledTimes(2);
  expect(f.diagnose).not.toHaveBeenCalled();
  expect(f.update).toHaveBeenLastCalledWith("video");
  expect(f.body.locked).toBe(true);
  expect(f.close).not.toHaveBeenCalled();
  f.stop();
  await vi.advanceTimersByTimeAsync(0);
  expect(f.signal().aborted).toBe(true);
  expect(f.body.locked).toBe(false);
  expect(f.close).toHaveBeenCalledOnce();
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

it("decodes a cached GOP burst in order, waiting for native capacity without dropping frames", async () => {
  const f = streamFixture(true, true);
  const nals = Array.from({ length: 13 }, (_, n) => {
    const bytes = packet(n === 0 ? 5 : 1);
    bytes[bytes.length - 1] = n;
    return bytes;
  });
  await vi.advanceTimersByTimeAsync(0);
  f.input.enqueue(concat([packet(7), packet(8), ...nals]));
  await vi.advanceTimersByTimeAsync(0);
  expect(f.decode).toHaveBeenCalledTimes(6);
  const remove = vi.spyOn(f.decoder(), "removeEventListener");
  const add = vi.spyOn(f.decoder(), "addEventListener");
  expect(f.diagnose).not.toHaveBeenCalled();
  f.drain();
  f.output();
  await vi.advanceTimersByTimeAsync(0);
  expect(f.decode).toHaveBeenCalledTimes(12);
  f.drain();
  f.output();
  await vi.advanceTimersByTimeAsync(0);
  expect(f.decode.mock.calls.map(([chunk]) => chunk.init)).toEqual(
    nals.map((nal, n) => ({
      type: n === 0 ? "key" : "delta",
      timestamp: n * 33333,
      data:
        n === 0
          ? concat([packet(7).slice(4), packet(8).slice(4), nal.slice(4)])
          : nal.slice(4),
    })),
  );
  expect(f.update).toHaveBeenLastCalledWith("video");
  expect(f.diagnose).not.toHaveBeenCalled();
  expect(remove).toHaveBeenCalledTimes(2);
  expect(add).toHaveBeenCalledTimes(1);
  f.stop();
  await vi.advanceTimersByTimeAsync(3000);
  expect(f.body.locked).toBe(false);
  expect(f.close).toHaveBeenCalledOnce();
  expect(f.fetchMock).toHaveBeenCalledTimes(1);
});

it.each(["watchdog", "leave-view"])(
  "releases a decoder capacity wait on %s",
  async (exit) => {
    const f = streamFixture(true, true);
    await vi.advanceTimersByTimeAsync(0);
    f.input.enqueue(
      concat([
        packet(7),
        packet(8),
        packet(5),
        ...Array.from({ length: 8 }, () => packet(1)),
      ]),
    );
    await vi.advanceTimersByTimeAsync(0);
    expect(f.decode).toHaveBeenCalledTimes(6);
    const remove = vi.spyOn(f.decoder(), "removeEventListener");
    const removeAbort = vi.spyOn(f.signal(), "removeEventListener");
    if (exit === "leave-view") f.stop();
    await vi.advanceTimersByTimeAsync(2000);
    expect(f.decode).toHaveBeenCalledTimes(6);
    expect(f.close).toHaveBeenCalledOnce();
    expect(remove).toHaveBeenCalledExactlyOnceWith(
      "dequeue",
      expect.any(Function),
    );
    expect(removeAbort).toHaveBeenCalledWith("abort", expect.any(Function));
    expect(f.signal().aborted).toBe(true);
    expect(f.body.locked).toBe(false);
    if (exit === "watchdog") {
      expect(f.diagnose).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({
          reason: "first-frame-timeout",
          decodeCount: 6,
          outputCount: 0,
          maxQueue: 6,
        }),
      );
    } else expect(f.diagnose).not.toHaveBeenCalled();
    f.stop();
    const count = f.fetchMock.mock.calls.length;
    f.drain();
    await vi.advanceTimersByTimeAsync(3000);
    expect(f.decode).toHaveBeenCalledTimes(6);
    expect(f.fetchMock).toHaveBeenCalledTimes(count);
  },
);
