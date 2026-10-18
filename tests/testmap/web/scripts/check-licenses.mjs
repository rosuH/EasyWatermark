import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
const require = createRequire(import.meta.url);
const root = new URL("..", import.meta.url);
const html = readFileSync(new URL("dist/index.html", root), "utf8");
const escapeHtml = (text) =>
  text
    .replace(/[ \t]+$/gm, "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
assert.deepEqual(readdirSync(new URL("dist", root)), ["index.html"]);
const section = html.match(
  /<details id="third-party-licenses"[\s\S]*?<\/details>/,
)?.[0];
assert(section, "Single-file page must retain readable license notices");
const pkg = JSON.parse(readFileSync(new URL("package.json", root), "utf8"));
for (const [name, version] of Object.entries(pkg.dependencies)) {
  assert(
    section.includes(`${name}@${version}`),
    `Missing runtime notice: ${name}`,
  );
  // Some packages restrict package.json exports; resolve their entry then
  // walk upward until the named package manifest is found.
  let dir = dirname(require.resolve(name));
  for (;;) {
    try {
      if (
        JSON.parse(readFileSync(join(dir, "package.json"), "utf8")).name ===
        name
      )
        break;
    } catch {}
    const parent = dirname(dir);
    assert.notEqual(parent, dir, `Cannot locate ${name}`);
    dir = parent;
  }
  const licenses = readdirSync(dir).filter((file) =>
    /^(licen[cs]e|copying|notice)([.-].*)?$/i.test(file),
  );
  assert(licenses.length, `Missing installed license: ${name}`);
  for (const file of licenses)
    assert(
      section.includes(
        escapeHtml(readFileSync(join(dir, file), "utf8").trim()),
      ),
      `Incomplete notice: ${name}/${file}`,
    );
}
for (const name of [
  "scheduler@",
  "@rollup/plugin-commonjs generated helper",
  "Copyright (c) 2019 RollupJS Plugin Contributors",
  "d3-zoom@",
  "@xyflow/system@",
  "@radix-ui/react-compose-refs@1.1.2",
  "tailwindcss@",
  "vite@",
  "shadcn/ui Button pattern",
])
  assert(
    section.includes(name),
    `Missing generated or transitive notice: ${name}`,
  );
for (const file of ["shadcn-ui.LICENSE.txt", "radix-primitives.LICENSE.txt"])
  assert(
    section.includes(
      escapeHtml(
        readFileSync(new URL(`licenses/${file}`, root), "utf8").trim(),
      ),
    ),
    `Incomplete vendored notice: ${file}`,
  );
assert(!section.includes("/private/tmp/"), "Notices must not embed host paths");
console.log(
  `Single HTML contains ${section.match(/<section>/g).length} license notices; runtime license texts verified.`,
);

const helperLicense = readFileSync(
  join(dirname(require.resolve("vite/package.json")), "LICENSE.md"),
  "utf8",
)
  .split("\n## ")
  .find((part) => part.split("\n")[0].includes("@rollup/plugin-commonjs"));
assert(
  helperLicense && section.includes(escapeHtml(helperLicense.trim())),
  "Incomplete generated CommonJS helper notice",
);
