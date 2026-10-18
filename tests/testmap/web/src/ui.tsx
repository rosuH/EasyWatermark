import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import {
  Check,
  Circle,
  CircleAlert,
  Clock3,
  Copy,
  Minus,
  Square,
  X,
} from "lucide-react";
import { Button } from "./components/ui/button";
import type { Lang } from "./model";
export const Language = createContext<Lang>("en");
export function useT() {
  const lang = useContext(Language);
  return useCallback(
    (en: string, zh: string) => (lang === "zh" ? zh : en),
    [lang],
  );
}
export function Status({ state = "uncovered" }: { state?: string }) {
  const t = useT();
  const labels: Record<string, string> = {
    passed: t("Passed", "通过"),
    failed: t("Failed", "失败"),
    review_required: t("Needs review", "待审"),
    running: t("Running", "运行中"),
    paused: t("Paused", "已暂停"),
    pending: t("Pending", "等待"),
    uncovered: t("Uncovered", "未覆盖"),
    stopped: t("Stopped", "已停止"),
    interrupted: t("Interrupted", "已中断"),
    skipped: t("Skipped", "跳过"),
    idle: t("Idle", "空闲"),
    blocked: t("Blocked", "受阻"),
    current: t("Current", "当前"),
    done: t("Done", "完成"),
  };
  const Icon = ["passed", "done"].includes(state)
    ? Check
    : ["failed", "interrupted"].includes(state)
      ? X
      : state === "review_required"
        ? CircleAlert
        : ["running", "current", "pending"].includes(state)
          ? Clock3
          : state === "stopped"
            ? Square
            : state === "uncovered"
              ? Minus
              : Circle;
  return (
    <span className={`status status-${state}`}>
      <Icon size={13} aria-hidden="true" />
      <span>{labels[state] || state}</span>
    </span>
  );
}
export function CopyButton({
  value,
  label,
}: {
  value: string;
  label?: string;
}) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  return (
    <Button
      size="sm"
      variant="ghost"
      title={value}
      aria-label={label || t("Copy reference", "复制引用")}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setCopied(true);
          timer.current = setTimeout(() => setCopied(false), 1500);
        } catch {
          setCopied(false);
        }
      }}
    >
      {copied ? <Check size={14} /> : <Copy size={14} />}{" "}
      {copied ? t("Copied", "已复制") : label}
    </Button>
  );
}
export function Empty({ title, detail }: { title: string; detail?: string }) {
  return (
    <div className="empty">
      <Circle size={24} />
      <strong>{title}</strong>
      {detail && <p>{detail}</p>}
    </div>
  );
}
export function ErrorNotice({ children }: { children: React.ReactNode }) {
  return (
    <div className="error" role="alert">
      <CircleAlert size={16} />
      <span>{children}</span>
    </div>
  );
}
export function Screenshot({ src, label }: { src: string; label: string }) {
  const [failed, setFailed] = useState(false);
  const t = useT();
  return failed ? (
    <p className="muted">
      {t("Image unavailable", "截图无法加载")}: {label}
    </p>
  ) : (
    <a href={src} target="_blank" rel="noreferrer" className="shot-link">
      <img
        src={src}
        alt={label}
        loading="lazy"
        onError={() => setFailed(true)}
      />
      <span>{label}</span>
    </a>
  );
}
