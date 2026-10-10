import { useEffect, useState } from "react";
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  const body = await response.json();
  if (!response.ok)
    throw new ApiError(
      response.status,
      body.error || `${response.status} ${response.statusText}`,
    );
  return body as T;
}
export function post<T>(path: string, body: unknown) {
  return api<T>(path, { method: "POST", body: JSON.stringify(body) });
}
export function useVisible() {
  const [visible, setVisible] = useState(document.visibilityState !== "hidden");
  useEffect(() => {
    const update = () => setVisible(document.visibilityState !== "hidden");
    document.addEventListener("visibilitychange", update);
    return () => document.removeEventListener("visibilitychange", update);
  }, []);
  return visible;
}
// A single request at a time; hide/unmount aborts the request and its next tick.
export function useResource<T>(
  url: string | null,
  interval = 0,
  revision = 0,
): { data?: T; error?: string } {
  const [value, setValue] = useState<{ url: string; data?: T; error?: string }>(
    { url: "" },
  );
  const visible = useVisible();
  useEffect(() => {
    if (!url || !visible) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      let failed = false;
      try {
        const data = await api<T>(url, { signal: controller.signal });
        if (!controller.signal.aborted)
          setValue((old) =>
            old.url === url &&
            !old.error &&
            JSON.stringify(old.data) === JSON.stringify(data)
              ? old
              : { url, data },
          );
      } catch (error) {
        failed = true;
        if (!controller.signal.aborted)
          setValue((old) => ({
            url,
            data: old.url === url ? old.data : undefined,
            error: error instanceof Error ? error.message : "Connection lost",
          }));
      }
      if (!controller.signal.aborted && interval)
        timer = setTimeout(poll, failed ? Math.max(interval, 4000) : interval);
    };
    void poll();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [url, interval, revision, visible]);
  return value.url === url ? value : {};
}
