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
export function watchMedia(
  canvas: HTMLCanvasElement,
  platform: string,
  device: string,
  update: (state: string) => void,
) {
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
  const useStill = () => {
    if (fallback || controller.signal.aborted) return;
    fallback = true;
    clearTimeout(watchdog);
    videoAbort.abort();
    if (decoder && decoder.state !== "closed") decoder.close();
    clear();
    update("connecting");
    void still();
  };
  const arm = (ms: number) => {
    clearTimeout(watchdog);
    watchdog = setTimeout(useStill, ms);
  };
  const video = async () => {
    if (typeof VideoDecoder === "undefined") {
      useStill();
      return;
    }
    arm(2000);
    let sps: Uint8Array | undefined;
    let pps: Uint8Array | undefined;
    let codec = "avc1.42E01E";
    let ts = 0;
    let keySeen = false;
    let buffer: Uint8Array = new Uint8Array();
    try {
      const response = await fetch(`/api/device-video?${query}`, {
        signal: videoAbort.signal,
      });
      if (!response.ok || response.status === 204 || !response.body)
        throw new Error("No video");
      const reader = response.body.getReader();
      while (!controller.signal.aborted && !fallback) {
        const next = await reader.read();
        if (next.done) throw new Error("Video ended");
        const parsed = packets(concat([buffer, next.value]));
        buffer = parsed.rest;
        for (const payload of parsed.payloads) {
          if (!payload.length) continue;
          if (payload[0] === 123) {
            const cfg = JSON.parse(new TextDecoder().decode(payload));
            codec = cfg.codec || codec;
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
          if (!key && !keySeen) continue;
          if (!decoder) {
            decoder = new VideoDecoder({
              output: (frame) => {
                try {
                  if (!fallback && !controller.signal.aborted) {
                    draw(frame, frame.displayWidth, frame.displayHeight);
                    update("video");
                    arm(1200);
                  }
                } finally {
                  frame.close();
                }
              },
              error: useStill,
            });
            decoder.configure({ codec, optimizeForLatency: true });
          }
          if (fallback) break;
          keySeen = true;
          const data = key
            ? concat([...(sps ? [sps] : []), ...(pps ? [pps] : []), payload])
            : payload;
          // Delta frames may reference queued frames. End this stream rather
          // than skip arbitrary references and paint a corrupted live view.
          if (decoder.decodeQueueSize > 5) {
            useStill();
            break;
          }
          decoder.decode(
            new EncodedVideoChunk({
              type: key ? "key" : "delta",
              timestamp: ts++ * 33333,
              data,
            }),
          );
        }
      }
    } catch {
      if (!controller.signal.aborted && !fallback) useStill();
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
