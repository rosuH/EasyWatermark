import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
const hash = () =>
  createHash("sha256")
    .update(readFileSync(new URL("../dist/index.html", import.meta.url)))
    .digest("hex");
const before = hash();
execFileSync("pnpm", ["build"], {
  stdio: "inherit",
  cwd: new URL("..", import.meta.url),
});
if (hash() !== before)
  throw new Error(
    "dist/index.html differs from source. Commit the rebuilt output.",
  );
console.log("dist/index.html matches the source.");

execFileSync(process.execPath, ["scripts/check-licenses.mjs"], {
  stdio: "inherit",
  cwd: new URL("..", import.meta.url),
});
