#!/usr/bin/env python3
"""Owned, initially empty iOS Simulator pick/edit/export file oracle.

prepare(udid, folder, marker), verify(state, evidence), restore(state, evidence)
mirror the Android evidence API. The caller sets ui_success after the controller
passes and copies fresh tmp/ewm_out_* into evidence/ios-app-output BEFORE closing
the product. state_path is the durable prepare journal; baseline_tmp is a list of
preexisting tmp basenames. No device access occurs on import or --self-check.

Only TESTMAP_IOS_OWNED_DEVICE pointing at the repository's existing
build/agent-device/ios-hybrid-owned-device.json authorizes device access. Cleanup
restores preferences, revalidates every active Photos resource, then destroys only
that disposable simulator. Unknown assets block destruction. No PhotoKit deletion
or Recently Deleted purge is performed.
"""

from __future__ import annotations

import json
import os
import plistlib
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import uuid
from pathlib import Path

from testmap_pick_export_evidence import _bands, _decode_png, _hash, _save
from testmap_setup import IOS_BUNDLE, REPO_ROOT, _run, make_fixtures

HELPER = "me.rosuh.easywatermark.testmap.photos-oracle"
OWNERSHIP = REPO_ROOT / "build/agent-device/ios-hybrid-owned-device.json"
SWIFT = REPO_ROOT / "scripts/testmap_ios_photos.swift"
PREFERENCES = ("Documents/sp_water_mark_config.preferences_pb",
               "Documents/sp_water_mark_user_config.preferences_pb",
               f"Library/Preferences/{IOS_BUNDLE}.plist")


def _simctl(*args: str) -> str:
    return _run(["xcrun", "simctl", *map(str, args)], timeout=60)


def _devices() -> list[dict]:
    raw = json.loads(_simctl("list", "devices", "--json"))
    if not isinstance(raw, dict) or not isinstance(raw.get("devices"), dict):
        raise ValueError("Malformed simulator inventory")
    rows = []
    for runtime, devices in raw["devices"].items():
        if not isinstance(devices, list) or any(not isinstance(d, dict) for d in devices):
            raise ValueError("Malformed simulator device rows")
        rows.extend(dict(d, runtime=runtime) for d in devices)
    return rows


def _guard(udid: str, state: dict | None = None, *, booted: bool = True) -> dict:
    path = Path(os.environ.get("TESTMAP_IOS_OWNED_DEVICE", ""))
    if not path.is_absolute() or path.is_symlink() or path.resolve() != OWNERSHIP.resolve():
        raise ValueError("TESTMAP_IOS_OWNED_DEVICE must point to the existing dedicated ownership file")
    metadata = json.loads(path.read_text())
    if (not isinstance(metadata, dict) or metadata.get("created_for_this_run") is not True
            or metadata.get("destroyed") or metadata.get("udid") != udid
            or not re.fullmatch(r"[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}", udid)
            or not isinstance(metadata.get("name"), str)
            or not metadata["name"].startswith("EWM-Hybrid-Owned-")):
        raise ValueError("Refusing an unowned, reused, or mismatched simulator")
    if state is not None and metadata != state["owned_device"]:
        raise ValueError("Ownership metadata changed since prepare")
    found = [d for d in _devices() if d.get("udid") == udid]
    if (len(found) != 1 or found[0].get("name") != metadata["name"]
            or found[0].get("isAvailable") is not True
            or not re.fullmatch(r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-(?:2[6-9]|[3-9]\d)-[\d-]+", found[0]["runtime"])
            or (booted and found[0].get("state") != "Booted")):
        raise ValueError("Owned simulator does not match the live simctl inventory")
    device = found[0]
    data = Path(device["dataPath"]).resolve()
    if data.name != "data" or data.parent.name != udid or not data.is_dir():
        raise ValueError("Simulator data directory identity is ambiguous")
    if state is not None and str(data) != state["device_data"]:
        raise ValueError("Simulator data directory changed")
    return {"metadata": metadata, "data": str(data)}


def _journal(state: dict) -> None:
    _save(Path(state["state_path"]), state)


def _file(root: Path, relative: str) -> Path:
    root = root.resolve()
    target = root / relative
    if (Path(relative).is_absolute() or ".." in Path(relative).parts
            or not target.resolve().is_relative_to(root)
            or any(p.is_symlink() for p in [target, *target.parents] if p != root and p.is_relative_to(root))):
        raise ValueError("Refusing a symlink or path outside the owned container")
    return target


def _container(state: dict, bundle: str = IOS_BUNDLE) -> Path:
    _guard(state["udid"], state)
    path = Path(_simctl("get_app_container", state["udid"], bundle, "data")).resolve()
    parent = Path(state["device_data"]) / "Containers/Data/Application"
    if path.parent != parent or not path.is_dir():
        raise ValueError("App container is outside the owned simulator")
    key = "container" if bundle == IOS_BUNDLE else "helper_container"
    if state.get(key) and str(path) != state[key]:
        raise ValueError("App container changed since prepare")
    return path


def _terminate(state: dict) -> None:
    _guard(state["udid"], state)
    result = subprocess.run(["xcrun", "simctl", "terminate", state["udid"], IOS_BUNDLE],
                            capture_output=True, text=True, timeout=30)
    message = result.stderr + result.stdout
    if result.returncode and not any(s in message.lower() for s in (
            "found nothing to terminate", "not running", "no such process")):
        raise ValueError(f"Could not terminate product: {message.strip()}")


def _photos_add_status(state: dict) -> list:
    db = _file(Path(state["device_data"]), "Library/TCC/TCC.db")
    if not db.exists():
        return []
    with sqlite3.connect(db.as_uri() + "?mode=ro", uri=True) as connection:
        return connection.execute(
            "SELECT auth_value FROM access WHERE service=? AND client=?",
            ("kTCCServicePhotosAdd", IOS_BUNDLE),
        ).fetchall()


def _build_helper(state: dict) -> Path:
    app = Path(state["folder"]) / "PhotosOracle.app"
    app.mkdir()
    with (app / "Info.plist").open("wb") as output:
        plistlib.dump({"CFBundleIdentifier": HELPER, "CFBundleExecutable": "PhotosOracle",
                       "CFBundleName": "PhotosOracle", "CFBundlePackageType": "APPL",
                       "CFBundleVersion": "1", "CFBundleShortVersionString": "1.0",
                       "MinimumOSVersion": "26.0", "LSRequiresIPhoneOS": True,
                       "UIDeviceFamily": [1, 2],
                       "NSPhotoLibraryUsageDescription": "Inspect this owned synthetic test library.",
                       "NSPhotoLibraryAddUsageDescription": "Import one owned synthetic fixture."}, output)
    sdk = _run(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"])
    _run(["xcrun", "--sdk", "iphonesimulator", "swiftc", "-parse-as-library",
          "-target", "arm64-apple-ios26.0-simulator", "-sdk", sdk,
          "-framework", "UIKit", "-framework", "Photos", str(SWIFT),
          "-o", str(app / "PhotosOracle")], timeout=180)
    _run(["codesign", "--force", "--sign", "-", "--timestamp=none", str(app)])
    return app


def _response(raw: dict, action: str) -> dict:
    if not isinstance(raw, dict) or raw.get("success") is not True or raw.get("action") != action:
        raise ValueError(f"PhotoKit {action} failed: {raw}")
    return raw


def _request(state: dict, action: str, **payload) -> dict:
    documents = _container(state, HELPER) / "Documents"
    token = uuid.uuid4().hex
    request, result = f"request-{token}.json", f"result-{token}.json"
    state["pending_action"] = {"action": action, **payload, "result": result}
    _journal(state)  # Durable intent precedes every helper launch/mutation.
    _save(_file(documents, request), {"action": action, **payload})
    _simctl("launch", "--terminate-running-process", state["udid"], HELPER,
            "--request", request, "--result", result)
    deadline = time.monotonic() + 45
    result_file = _file(documents, result)
    while time.monotonic() < deadline:
        if result_file.exists():
            raw = json.loads(result_file.read_text())
            if raw.get("error") != "request in progress":
                response = _response(raw, action)
                # Preserve the exact mutation receipt before returning to the caller.
                _save(Path(state["folder"]) / f"{result}", response)
                state["last_receipt"] = str(Path(state["folder"]) / result)
                state.pop("pending_action", None)
                _journal(state)
                return response
        time.sleep(0.1)
    raise ValueError(f"PhotoKit {action} timed out; pending intent retained in journal")


def _assets(raw: dict) -> dict[str, dict]:
    rows = _response(raw, "inventory").get("assets")
    if not isinstance(rows, list):
        raise ValueError("Missing PhotoKit assets; errors are never an empty baseline")
    result = {}
    for row in rows:
        if (not isinstance(row, dict) or not isinstance(row.get("id"), str)
                or not row["id"].strip() or row["id"] in result
                or not isinstance(row.get("resources"), list) or not row["resources"]):
            raise ValueError("Missing, duplicate, or ambiguous PhotoKit identity/resources")
        indices = []
        for resource in row["resources"]:
            if (not isinstance(resource, dict) or type(resource.get("index")) is not int
                    or resource["index"] < 0 or not isinstance(resource.get("contentType"), str)):
                raise ValueError("Malformed PhotoKit resource")
            indices.append(resource["index"])
        if len(set(indices)) != len(indices):
            raise ValueError("Duplicate PhotoKit resource index")
        result[row["id"]] = row
    return result


def _inventory(state: dict) -> dict[str, dict]:
    return _assets(_request(state, "inventory"))


def _export(state: dict, asset: dict, destination: Path) -> dict:
    resources = asset["resources"]
    if (len(resources) != 1 or resources[0].get("contentType") != "public.png"
            or resources[0].get("type") != 1 or asset.get("width") != 960 or asset.get("height") != 640):
        raise ValueError("Expected exactly one original PNG resource, 960x640")
    receipt = _request(state, "export", assetID=asset["id"], resourceIndex=resources[0]["index"])
    name = receipt.get("file")
    if (not isinstance(name, str) or Path(name).name != name or "\\" in name
            or not re.fullmatch(r"resource-[0-9A-Fa-f-]+\.png", name)
            or receipt.get("id") != asset["id"] or receipt.get("resource") != resources[0]):
        raise ValueError("PhotoKit resource receipt identity mismatch")
    payload = _file(_container(state, HELPER) / "Documents", name).read_bytes()
    if not payload or _hash(payload) != receipt.get("sha256") or len(payload) != receipt.get("byteCount"):
        raise ValueError("PhotoKit exported resource hash/size mismatch")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(payload)
    return {"id": asset["id"], "path": str(destination), "sha256": _hash(payload),
            "size": len(payload), "resource": resources[0]}


def _app_output(state: dict, evidence: Path) -> dict:
    directory = evidence / "ios-app-output"
    files = list(directory.glob("ewm_out_*"))
    if len(files) != 1:
        raise ValueError(f"Expected one copied fresh app tmp/ewm_out_* output, got {len(files)}")
    path = _file(directory, files[0].name)
    if (not re.fullmatch(r"ewm_out_[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\.png", path.name)
            or path.name in state["baseline_tmp"] or not path.is_file()):
        raise ValueError("App output is not a fresh product PNG")
    return {"path": str(path), "sha256": _hash(path.read_bytes()), "name": path.name}


def prepare(udid: str, folder: Path, marker: str) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", marker):
        raise ValueError("marker must be 1..64 ASCII letters/digits/underscore/hyphen")
    owned = _guard(udid)
    if not shutil.which("magick"):
        raise ValueError("Installed magick is required to decode real PNG pixels")
    work = Path(folder).resolve() / "pick-export"
    work.mkdir(parents=True, exist_ok=False)
    state = {"platform": "ios", "folder": str(work), "state_path": str(work / "prepare.json"),
             "udid": udid, "marker": marker, "marker_text": f"EWM BENCH {marker}",
             "owned_device": owned["metadata"], "device_data": owned["data"],
             "baseline_ids": [], "owned_ids": [], "owned_hashes": {}, "baseline_tmp": [],
             "preferences": {}, "source": {}, "prepared": False, "ui_success": False,
             "baseline_empty_verified": False, "photos_add_grant_attempted": False}
    _journal(state)
    try:
        state["container"] = str(_container(state))
        if _photos_add_status(state):
            raise ValueError("Product photos-add status is already declared; refusing reused device")
        state["photos_add_initial_status"] = "undeclared"
        _journal(state)
        _terminate(state)
        container = Path(state["container"])
        for relative in PREFERENCES:
            path = _file(container, relative)
            data = path.read_bytes() if path.exists() else None
            backup = work / (path.name + ".backup")
            if data is not None:
                backup.write_bytes(data)
            state["preferences"][relative] = {"path": str(backup) if data is not None else None,
                                               "sha256": _hash(data) if data is not None else None}
            _journal(state)
        state["preference_baseline_sha256"] = _hash(json.dumps(
            {k: v["sha256"] for k, v in state["preferences"].items()}, sort_keys=True).encode())
        state["baseline_tmp"] = sorted(p.name for p in _file(container, "tmp").iterdir())
        _journal(state)
        helper = _build_helper(state)
        state["helper_install_attempted"] = True
        _journal(state)
        _guard(udid, state)
        _simctl("install", udid, str(helper))
        state["helper_container"] = str(_container(state, HELPER))
        state["helper_photos_grant_attempted"] = True
        _journal(state)
        _guard(udid, state)
        # Preserve a real UI authorization on this disposable simulator. Replacing
        # it with simctl's TCC grant makes PhotoKit ask again on iOS 26.5.
        db = Path(state["device_data"]) / "Library/TCC/TCC.db"
        with sqlite3.connect(db.as_uri() + "?mode=ro", uri=True) as connection:
            granted = connection.execute("SELECT auth_value,auth_reason FROM access WHERE service=? AND client=?",
                                         ("kTCCServicePhotos", HELPER)).fetchall() == [(2, 2)]
        if not granted:
            _simctl("privacy", udid, "grant", "photos", HELPER)
        baseline = _inventory(state)
        state["baseline_ids"] = sorted(baseline)
        state["baseline_inventory_receipt"] = state["last_receipt"]
        _journal(state)
        if baseline:
            raise ValueError("Photos baseline is nonempty; refusing import and teardown")
        state["baseline_empty_verified"] = True
        source = make_fixtures(work / "fixtures", marker)["A"]
        _decode_png(source)
        state["source"] = {"path": str(source), "sha256": _hash(source.read_bytes()), "id": None,
                           "picker_photo_label": "The only owned synthetic photo: dark blue top band, plain white center, gold/yellow bottom band (960x640)."}
        _journal(state)
        documents = Path(state["helper_container"]) / "Documents"
        _file(documents, source.name).write_bytes(source.read_bytes())
        pre_import = _inventory(state)
        state["pre_import_inventory_receipt"] = state["last_receipt"]
        state["pre_import_ids"] = sorted(pre_import)
        state["pre_import_empty_verified"] = not pre_import
        _journal(state)
        if pre_import:
            raise ValueError("Photos changed before fixture import")
        receipt = _request(state, "import", sourceFile=source.name, runMarker=marker)
        if (not isinstance(receipt.get("id"), str) or not receipt["id"].strip()
                or receipt.get("sha256") != state["source"]["sha256"]
                or receipt.get("byteCount") != source.stat().st_size):
            raise ValueError("Exact fixture import receipt is missing or mismatched")
        state["source"].update(id=receipt["id"], import_receipt=state["last_receipt"])
        state["owned_ids"] = [receipt["id"]]
        state["owned_hashes"][receipt["id"]] = receipt["sha256"]
        _journal(state)
        deadline = time.monotonic() + 10
        while True:
            assets = _inventory(state)
            if assets or time.monotonic() >= deadline:
                break
            time.sleep(0.2)
        if set(assets) != {receipt["id"]}:
            raise ValueError("Photos does not contain exactly the imported owned fixture")
        exported = _export(state, assets[receipt["id"]], work / "source-photokit.png")
        if exported["sha256"] != state["source"]["sha256"]:
            raise ValueError("Imported PhotoKit fixture bytes differ from the synthetic source")
        if _photos_add_status(state):
            raise ValueError("Product photos-add changed before fixture grant")
        state["photos_add_grant_attempted"] = True
        _journal(state)
        _guard(udid, state)
        _simctl("privacy", udid, "grant", "photos-add", IOS_BUNDLE)
        if _photos_add_status(state) != [(2,)]:
            raise ValueError("Product photos-add fixture grant was not confirmed")
        state["prepared"] = True
        _journal(state)
        return state
    except Exception as exc:
        state["prepare_error"] = str(exc)
        _journal(state)
        cleanup = restore(state, work)
        raise RuntimeError(f"prepare failed: {exc}; cleanup: {cleanup['report_path']}") from exc


def verify(state: dict, evidence: Path) -> dict:
    evidence = Path(evidence).resolve()
    report = {"success": False, "file_checks_passed": False,
              "ui_success": state.get("ui_success") is True, "checks": {}, "outputs": [],
              "report_path": str(evidence / "pick-export-verification.json"),
              "limitations": ["UI acceptance comes from the caller.",
                              "Marker bytes and changed pixels do not prove exact rendered text."]}
    try:
        if state.get("platform") != "ios" or state.get("prepared") is not True:
            raise ValueError("iOS setup did not complete")
        _guard(state["udid"], state)
        source = state["source"]
        report["checks"]["source_hash_unchanged"] = _hash(Path(source["path"]).read_bytes()) == source["sha256"]
        assets = _inventory(state)
        report["inventory"] = list(assets.values())
        candidates = set(assets) - set(state["baseline_ids"]) - {source["id"]}
        if state["baseline_ids"] or source["id"] not in assets or len(candidates) != 1:
            raise ValueError("Expected source plus exactly one new PhotoKit asset, with empty baseline")
        report["checks"]["exactly_one_new_export"] = True
        output = _export(state, assets[candidates.pop()], evidence / "pick-export-output.png")
        app_output = _app_output(state, evidence)
        report["outputs"] = [output]
        report["app_output"] = app_output
        if output["sha256"] != app_output["sha256"] or output["sha256"] == source["sha256"]:
            raise ValueError("PhotoKit export does not match the fresh changed app output")
        # Inventory differences alone NEVER confer ownership.
        state["app_output"] = app_output
        state["owned_hashes"][output["id"]] = output["sha256"]
        state["owned_ids"] = sorted(state["owned_hashes"])
        _journal(state)
        report["checks"]["photokit_matches_fresh_app_output"] = True
        pixels, original = _decode_png(Path(output["path"])), _decode_png(Path(source["path"]))
        report["checks"]["png_decoded_960x640"] = True
        report["checks"]["output_pixels_differ_from_source"] = pixels != original
        report["source_band_ratios"] = _bands(pixels)
        report["checks"]["source_bands_retained"] = all(v >= 0.60 for v in report["source_band_ratios"].values())
        config = _file(_container(state), PREFERENCES[0])
        payload = config.read_bytes() if config.exists() else b""
        report["checks"]["marker_bytes_persisted"] = state["marker_text"].encode() in payload
        evidence.mkdir(parents=True, exist_ok=True)
        snapshot = evidence / "pick-export-watermark.preferences_pb"
        snapshot.write_bytes(payload)
        report["datastore"] = {"path": str(snapshot), "sha256": _hash(payload)}
        report["file_checks_passed"] = all(report["checks"].values())
        report["success"] = report["ui_success"] and report["file_checks_passed"]
    except Exception as exc:
        report["error"] = str(exc)
    _save(Path(report["report_path"]), report)
    return report


def _revalidated_ids(state: dict, evidence: Path) -> set[str]:
    if state.get("baseline_empty_verified") is not True or state["baseline_ids"]:
        raise ValueError("Destruction requires an observed empty Photos baseline")
    assets = _inventory(state)
    owned = set(state["owned_ids"])
    if not set(assets).issubset(owned) or owned != set(state["owned_hashes"]):
        raise ValueError("Unknown/concurrent Photos assets; preserving simulator")
    source = state.get("source", {})
    validated = set()
    for identity, asset in assets.items():
        if identity == source.get("id"):
            receipt = _response(json.loads(Path(source["import_receipt"]).read_text()), "import")
            if (receipt.get("id") != identity or receipt.get("sha256") != source["sha256"]
                    or _hash(Path(source["path"]).read_bytes()) != source["sha256"]):
                raise ValueError("Source import receipt/hash changed")
        else:
            app = _app_output(state, evidence)
            if (state.get("app_output") != app or app["sha256"] != state["owned_hashes"][identity]
                    or app["sha256"] == source.get("sha256")):
                raise ValueError("Export ownership lacks a fresh exact app-output hash")
        output = _export(state, asset, evidence / f"cleanup-resource-{len(validated)}.png")
        if output["sha256"] != state["owned_hashes"][identity]:
            raise ValueError("Owned PhotoKit resource changed; preserving simulator")
        validated.add(identity)
    if set(_inventory(state)) != validated:
        raise ValueError("Photos inventory changed during cleanup; preserving simulator")
    return validated


def restore(state: dict, evidence: Path) -> dict:
    evidence = Path(evidence).resolve()
    report = {"success": False, "errors": [], "preferences_restored": False,
              "method": "restore preference bytes, then destroy the guarded disposable simulator",
              "report_path": str(evidence / "pick-export-cleanup.json"),
              "limitations": ["PhotoKit deletion is soft deletion; this path instead removes the owned simulator and its data directory."]}
    try:
        _guard(state["udid"], state)
        state["restore_pending"] = True
        _journal(state)
        _terminate(state)
        container = _container(state)
        checks = {}
        for relative, item in state["preferences"].items():
            if relative not in PREFERENCES:
                raise ValueError("Unknown preference backup path")
            data = Path(item["path"]).read_bytes() if item["path"] is not None else None
            if (_hash(data) if data is not None else None) != item["sha256"]:
                raise ValueError(f"Preference backup hash mismatch: {relative}")
            target = _file(container, relative)
            if data is None:
                target.unlink(missing_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                temporary = _file(container, relative + ".ewm-restore.tmp")
                temporary.write_bytes(data)
                temporary.replace(target)
            restored = target.read_bytes() if target.exists() else None
            checks[relative] = {"expected_sha256": item["sha256"],
                                "actual_sha256": _hash(restored) if restored is not None else None,
                                "matches": restored == data, "absent": restored is None}
        report["preference_checks"] = checks
        report["preferences_restored"] = set(checks) == set(PREFERENCES) and all(c["matches"] for c in checks.values())
        if state.get("photos_add_grant_attempted"):
            _guard(state["udid"], state)
            _simctl("privacy", state["udid"], "reset", "photos-add", IOS_BUNDLE)
            report["photos_add_reset"] = not _photos_add_status(state)
        else:
            report["photos_add_reset"] = True
        _save(Path(report["report_path"]), report)  # Restoration evidence survives teardown.
        if not report["preferences_restored"] or not report["photos_add_reset"]:
            raise ValueError("Preference/permission restoration incomplete; preserving simulator")
        report["revalidated_owned_ids"] = sorted(_revalidated_ids(state, evidence))
        owned = _guard(state["udid"], state)
        state["teardown_pending"] = True
        _journal(state)
        _save(Path(report["report_path"]), report)
        _simctl("shutdown", state["udid"])
        _guard(state["udid"], state, booted=False)
        _simctl("delete", state["udid"])
        report["simulator_absent"] = not any(d.get("udid") == state["udid"] for d in _devices())
        report["data_directory_removed"] = not Path(owned["data"]).parent.exists()
        if not report["simulator_absent"] or not report["data_directory_removed"]:
            raise ValueError("Simulator deletion did not remove both inventory identity and data directory")
        metadata = json.loads(OWNERSHIP.read_text())
        if metadata != state["owned_device"]:
            raise ValueError("Ownership metadata changed during teardown")
        metadata.update(destroyed=True, destroyed_at=time.time())
        _save(OWNERSHIP, metadata)
        state["destroyed"] = True
        _journal(state)
        report["destroyed"] = True
        report["success"] = True
    except Exception as exc:
        report["errors"].append(str(exc))
    _save(Path(report["report_path"]), report)
    return report


def _self_check() -> None:
    import tempfile
    from unittest.mock import patch

    def rejected(call):
        try:
            call()
        except (ValueError, KeyError):
            return
        raise AssertionError("Unsafe ownership/parser input was accepted")

    row = {"id": "owned/L0/001", "width": 960, "height": 640,
           "resources": [{"index": 0, "type": 1, "contentType": "public.png"}]}
    inventory = {"success": True, "action": "inventory", "assets": [row]}
    assert _assets(inventory) == {row["id"]: row}
    assert _assets(dict(inventory, assets=[])) == {}
    for invalid in ({}, {"success": False, "action": "inventory", "assets": []},
                    dict(inventory, assets=[row, row]), dict(inventory, assets=None),
                    dict(inventory, assets=[dict(row, resources=[])])):
        rejected(lambda invalid=invalid: _assets(invalid))
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        ownership = root / "owned.json"
        udid = "00000000-1111-2222-3333-444444444444"
        data = root / udid / "data"
        data.mkdir(parents=True)
        metadata = {"udid": udid, "name": "EWM-Hybrid-Owned-check", "created_for_this_run": True}
        _save(ownership, metadata)
        device = dict(metadata, dataPath=str(data), state="Booted", isAvailable=True,
                      runtime="com.apple.CoreSimulator.SimRuntime.iOS-26-0")
        module = sys.modules[__name__]
        with patch.multiple(module, OWNERSHIP=ownership, _devices=lambda: [device],
                            _simctl=lambda *a: (_ for _ in ()).throw(AssertionError("No device access in self-check"))), \
                patch.dict(os.environ, TESTMAP_IOS_OWNED_DEVICE=str(ownership)):
            assert _guard(udid)["metadata"] == metadata
            rejected(lambda: _guard("other-device"))
            for changes in ({"created_for_this_run": False}, {"name": "personal-iPhone"}, {"destroyed": True}):
                _save(ownership, dict(metadata, **changes))
                rejected(lambda: _guard(udid))
            _save(ownership, metadata)
            device["name"] = "another-simulator"
            rejected(lambda: _guard(udid))
            device["name"] = metadata["name"]
            rejected(lambda: _file(root, "../elsewhere"))
            (root / "link").symlink_to(data, target_is_directory=True)
            rejected(lambda: _file(root, "link/file"))
            state = {"baseline_empty_verified": True, "baseline_ids": [],
                     "owned_ids": ["owned"], "owned_hashes": {"owned": "hash"}}
            with patch.object(module, "_inventory", return_value={"unknown": row}):
                rejected(lambda: _revalidated_ids(state, root))
            state["baseline_ids"] = ["baseline"]
            rejected(lambda: _revalidated_ids(state, root))
            directory = root / "ios-app-output"
            directory.mkdir()
            output_name = f"ewm_out_{udid}.png"
            (directory / output_name).write_bytes(b"synthetic")
            rejected(lambda: _app_output({"baseline_tmp": [output_name]}, root))
            assert _app_output({"baseline_tmp": []}, root)["sha256"] == _hash(b"synthetic")
            source_path, receipt_path = root / "source.png", root / "import-receipt.json"
            source_path.write_bytes(b"source")
            source_hash, output_hash = _hash(b"source"), _hash(b"synthetic")
            _save(receipt_path, {"success": True, "action": "import", "id": "source",
                                 "sha256": source_hash})
            state = {"baseline_empty_verified": True, "baseline_ids": [], "baseline_tmp": [],
                     "owned_ids": ["source", "export"],
                     "owned_hashes": {"source": source_hash, "export": output_hash},
                     "source": {"id": "source", "path": str(source_path), "sha256": source_hash,
                                "import_receipt": str(receipt_path)},
                     "app_output": _app_output({"baseline_tmp": []}, root)}
            with patch.object(module, "_inventory", return_value={"source": dict(row, id="source"),
                                                                    "export": dict(row, id="export")}), \
                    patch.object(module, "_export", side_effect=lambda s, a, p: {
                        "sha256": s["owned_hashes"][a["id"]]}):
                assert _revalidated_ids(state, root) == {"source", "export"}
                state["owned_hashes"]["export"] = "wrong-hash"
                rejected(lambda: _revalidated_ids(state, root))
                state["owned_hashes"]["export"] = output_hash
                source_path.write_bytes(b"changed-source")
                rejected(lambda: _revalidated_ids(state, root))
            (directory / "ewm_out_5678.png").write_bytes(b"ambiguous")
            rejected(lambda: _app_output({"baseline_tmp": []}, root))
    print("self-check passed: ownership, inventory/receipt parsing, unknown-asset teardown refusal, fresh-output guards; no device access")


if __name__ == "__main__":
    if sys.argv[1:] != ["--self-check"]:
        raise SystemExit("Use --self-check; prepare/verify/restore are importable helpers")
    _self_check()
