#!/usr/bin/env python3
"""Regression check: Maven groups must not override Android package identity."""

import importlib
from pathlib import Path
import sys
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[1]
MIRRORS = ("skills", ".agents/skills", ".claude/skills")
CASES = [
    (f'{key} = "org.example.maven"', "")
    for key in ("group", "groupId")
] + [
    (f'{key} = project.property("mavenGroup")', "mavenGroup=org.example.maven")
    for key in ("group", "groupId")
] + [("", f"{key}=org.example.maven") for key in ("group", "groupId", "GROUP")]


def main():
    failures = []
    checks = 0
    for mirror in MIRRORS:
        script_dir = ROOT / mirror / "play-policy-insights/scripts"
        sys.path.insert(0, str(script_dir))
        try:
            orchestrator = importlib.import_module("orchestrator")
            with TemporaryDirectory() as temp:
                app_dir = Path(temp)
                gradle = app_dir / "build.gradle.kts"
                manifest = app_dir / "AndroidManifest.xml"
                manifest.write_text(
                    '<manifest package="org.example.realapp" />', encoding="utf-8"
                )
                for group, properties in CASES:
                    for explicit_id in (False, True):
                        gradle.write_text(
                            'plugins { id("com.android.application") }\n'
                            + group
                            + ('\nandroid { defaultConfig { applicationId = "org.example.explicit" } }'
                               if explicit_id else ""),
                            encoding="utf-8",
                        )
                        (app_dir / "gradle.properties").write_text(properties, encoding="utf-8")
                        lookups = orchestrator.load_gradle_properties(temp)
                        gradles, manifests = [str(gradle)], [str(manifest)]
                        modules = orchestrator.parse_application_modules(
                            gradles, manifests, lookups, {}
                        )
                        identity, _, _ = orchestrator.determine_primary_identity(
                            modules, gradles, manifests, lookups, {}, {}, temp
                        )
                        expected = "org.example.explicit" if explicit_id else "org.example.realapp"
                        if identity != expected:
                            failures.append(f"{mirror}: {group or properties}, explicit={explicit_id}: {identity!r} != {expected!r}")
                        checks += 1
        finally:
            sys.path.pop(0)
            for name in ("orchestrator", "scanner", "template_engine"):
                sys.modules.pop(name, None)
    assert not failures, "\n" + "\n".join(failures)
    print(f"PASS: {checks} identity checks across {len(MIRRORS)} skill mirrors")


if __name__ == "__main__":
    main()
