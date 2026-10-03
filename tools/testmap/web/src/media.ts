// Existing console framing: big-endian packet length, JSON config or Annex-B NAL.
export function concat(parts: Uint8Array[]) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}
export function naluType(bytes: Uint8Array) {
  const offset = bytes[2] === 1 ? 3 : bytes[3] === 1 ? 4 : 0;
  return (bytes[offset] || 0) & 31;
}
export function packets(buffer: Uint8Array): {
  payloads: Uint8Array[];
  rest: Uint8Array;
} {
  const payloads: Uint8Array[] = [];
  let offset = 0;
  while (buffer.length - offset >= 4) {
    const size = new DataView(
      buffer.buffer,
      buffer.byteOffset + offset,
      4,
    ).getUint32(0);
    if (size > 8_000_000) throw new Error("Invalid video packet");
    if (buffer.length - offset < 4 + size) break;
    payloads.push(buffer.slice(offset + 4, offset + 4 + size));
    offset += 4 + size;
  }
  return { payloads, rest: buffer.slice(offset) };
}
export interface VideoDiagnostic {
  reason:
    | "webcodecs-unavailable"
    | "first-frame-timeout"
    | "frame-timeout"
    | "http-error"
    | "stream-ended"
    | "stream-error"
    | "packet-error"
    | "configure-error"
    | "decode-error"
    | "decoder-error"
    | "decoder-backlog";
  elapsedMs: number;
  firstPacketMs: number | null;
  firstIdrMs: number | null;
  firstOutputMs: number | null;
  codec: string;
  decodeCount: number;
  outputCount: number;
  maxQueue: number;
  errorName?: string;
  errorMessage?: string;
}
export function watchMedia(
  canvas: HTMLCanvasElement,
  platform: string,
  device: string,
  update: (state: string) => void,
  diagnose?: (diagnostic: VideoDiagnostic) => void,
) {
  const started = performance.now();
  const elapsed = () => Math.round(performance.now() - started);
  const stats = {
    firstPacketMs: null as number | null,
    firstIdrMs: null as number | null,
    firstOutputMs: null as number | null,
    codec: "avc1.42E01E",
    decodeCount: 0,
    outputCount: 0,
    maxQueue: 0,
  };
  const controller = new AbortController();
  let videoAbort = new AbortController();
  let decoder: VideoDecoder | undefined;
  let timer: ReturnType<typeof setTimeout>;
  let watchdog: ReturnType<typeof setTimeout>;
  let fallback = false;
  const query = new URLSearchParams({ platform, device });
  const clear = () =>
    canvas.getContext("2d")?.clearRect(0, 0, canvas.width, canvas.height);
  const draw = (frame: CanvasImageSource, width: number, height: number) => {
    if (controller.signal.aborted) return;
    canvas.width = width;
    canvas.height = height;
    canvas.style.aspectRatio = `${width}/${height}`;
    canvas.getContext("2d")?.drawImage(frame, 0, 0);
  };
  const still = async () => {
    if (controller.signal.aborted) return;
    try {
      const res = await fetch(`/api/device-frame?${query}`, {
        signal: controller.signal,
      });
      if (!res.ok || res.status === 204) throw new Error("No frame");
      const bitmap = await createImageBitmap(await res.blob());
      draw(bitmap, bitmap.width, bitmap.height);
      bitmap.close();
      update("still");
    } catch {
      if (!controller.signal.aborted) {
        clear();
        update("disconnected");
      }
    }
    if (!controller.signal.aborted) timer = setTimeout(still, 1000);
  };
  const useStill = (reason: VideoDiagnostic["reason"], error?: unknown) => {
    if (fallback || controller.signal.aborted) return;
    fallback = true;
    diagnose?.({
      ...stats,
      reason,
      elapsedMs: elapsed(),
      ...(error instanceof Error || error instanceof DOMException
        ? { errorName: error.name, errorMessage: error.message.slice(0, 500) }
        : {}),
    });
    clearTimeout(watchdog);
    videoAbort.abort();
    if (decoder && decoder.state !== "closed") decoder.close();
    clear();
    update("connecting");
    void still();
  };
  const arm = (ms: number, reason: VideoDiagnostic["reason"]) => {
    clearTimeout(watchdog);
    watchdog = setTimeout(() => useStill(reason), ms);
  };
  const video = async () => {
    if (typeof VideoDecoder === "undefined") {
      useStill("webcodecs-unavailable");
      return;
    }
    arm(2000, "first-frame-timeout");
    let sps: Uint8Array | undefined;
    let pps: Uint8Array | undefined;
    let phase: VideoDiagnostic["reason"] = "stream-error";
    let ts = 0;
    let keySeen = false;
    let buffer: Uint8Array = new Uint8Array();
    try {
      const response = await fetch(`/api/device-video?${query}`, {
        signal: videoAbort.signal,
      });
      if (!response.ok || response.status === 204 || !response.body) {
        useStill(
          "http-error",
          new Error(`HTTP ${response.status}: no video body`),
        );
        return;
      }
      const reader = response.body.getReader();
      while (!controller.signal.aborted && !fallback) {
        phase = "stream-error";
        const next = await reader.read();
        if (next.done) {
          useStill("stream-ended");
          return;
        }
        if (next.value.length && stats.firstPacketMs === null)
          stats.firstPacketMs = elapsed();
        phase = "packet-error";
        const parsed = packets(concat([buffer, next.value]));
        buffer = parsed.rest;
        for (const payload of parsed.payloads) {
          phase = "packet-error";
          if (!payload.length) continue;
          if (payload[0] === 123) {
            const cfg = JSON.parse(new TextDecoder().decode(payload));
            stats.codec = cfg.codec || stats.codec;
            continue;
          }
          const kind = naluType(payload);
          if (kind === 7) {
            sps = payload;
            continue;
          }
          if (kind === 8) {
            pps = payload;
            continue;
          }
          if (kind === 6 || kind === 9) continue;
          const key = kind === 5;
          if (key && stats.firstIdrMs === null) stats.firstIdrMs = elapsed();
          if (!key && !keySeen) continue;
          if (!decoder) {
            phase = "configure-error";
            decoder = new VideoDecoder({
              output: (frame) => {
                try {
                  if (!fallback && !controller.signal.aborted) {
                    if (stats.firstOutputMs === null)
                      stats.firstOutputMs = elapsed();
                    stats.outputCount++;
                    draw(frame, frame.displayWidth, frame.displayHeight);
                    update("video");
                    arm(1200, "frame-timeout");
                  }
                } finally {
                  frame.close();
                }
              },
              error: (error) => useStill("decoder-error", error),
            });
            decoder.configure({ codec: stats.codec, optimizeForLatency: true });
          }
          if (fallback) break;
          keySeen = true;
          const data = key
            ? concat([...(sps ? [sps] : []), ...(pps ? [pps] : []), payload])
            : payload;
          // Delta frames may reference queued frames. End this stream rather
          // than skip arbitrary references and paint a corrupted live view.
          stats.maxQueue = Math.max(stats.maxQueue, decoder.decodeQueueSize);
          if (decoder.decodeQueueSize > 5) {
            useStill("decoder-backlog");
            break;
          }
          phase = "decode-error";
          decoder.decode(
            new EncodedVideoChunk({
              type: key ? "key" : "delta",
              timestamp: ts++ * 33333,
              data,
            }),
          );
          stats.decodeCount++;
          stats.maxQueue = Math.max(stats.maxQueue, decoder.decodeQueueSize);
        }
      }
    } catch (error) {
      if (!controller.signal.aborted && !fallback) useStill(phase, error);
    }
  };
  update("connecting");
  void video();
  return () => {
    controller.abort();
    videoAbort.abort();
    clearTimeout(timer);
    clearTimeout(watchdog);
    if (decoder && decoder.state !== "closed") decoder.close();
    clear();
  };
}
