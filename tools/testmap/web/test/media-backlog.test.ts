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
    const stop = watchMedia(canvas, "android", "mock", update);
    await vi.advanceTimersByTimeAsync(0);
    expect(decode).toHaveBeenCalledTimes(1);
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
