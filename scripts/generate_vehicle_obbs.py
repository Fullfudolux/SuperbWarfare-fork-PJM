#!/usr/bin/env python3
"""
Auto-generate SBW per-cube OBB JSON from Bedrock geometry models.
Reads scripts/vehicles.json for vehicle configs.
Skips bones starting with "skip" (case-insensitive).
Only regenerates if model file changed (mtime check).
"""

import argparse
import json
import math
import os
from pathlib import Path
from collections import Counter

SCRIPT_DIR = Path(__file__).parent
PROJECT_DIR = SCRIPT_DIR.parent
VEHICLES_JSON = SCRIPT_DIR / "vehicles.json"

SKIP_BONES = set()
STEERING_WHEEL_BONES = set()
SKIP_ROTATION_BONES = set()  # Bones whose rotation is handled by runtime transform (like steering wheel)
WHEEL_TRANSFORMS = {}
config_bone_transforms = {}


def zyx_matrix(rx, ry, rz):
    """M = Rz(rz) @ Ry(ry) @ Rx(rx), applied to a column vector as v' = M @ v
    (Rx applied first, then Ry, then Rz). Verified against Blockbench's own
    get_world_boxes ground truth for pure-axis AND compound (2-3 axis) cube
    rotations -- the previous formula here only happened to be right for
    pure single-axis rotations and silently produced wrong world positions
    for any cube rotated on 2+ axes at once (very common: most turret/detail
    cubes with a CustomRotate on all three axes)."""
    sa, ca = math.sin(rx), math.cos(rx)
    sb, cb = math.sin(ry), math.cos(ry)
    sc, cc = math.sin(rz), math.cos(rz)
    return [
        [cc * cb, cc * sb * sa - sc * ca, cc * sb * ca + sc * sa],
        [sc * cb, sc * sb * sa + cc * ca, sc * sb * ca - cc * sa],
        [-sb,     cb * sa,                cb * ca               ],
    ]

def mat_mul(a, b):
    return [[sum(a[r][k]*b[k][c] for k in range(3)) for c in range(3)] for r in range(3)]

def mat_vec(m, v):
    return [sum(m[r][c]*v[c] for c in range(3)) for r in range(3)]

def lib_matrix(rot):
    # BedrockModel.java: X negated, Y negated, Z positive, then rotateZYX(z, y, x) = Rz*Ry*Rx
    rx = math.radians(-rot[0])
    ry = math.radians(-rot[1])
    rz = math.radians(rot[2])
    return zyx_matrix(rx, ry, rz)

def sbw_conj(m):
    s = [-1, 1, -1]
    return [[s[i]*s[j]*m[i][j] for j in range(3)] for i in range(3)]

def loader_euler(m):
    cx = math.asin(max(-1.0, min(1.0, -m[1][2])))
    cos_cx = math.cos(cx)
    if abs(cos_cx) > 1e-7:
        cy = math.atan2(m[0][2], m[2][2])
        cz = math.atan2(m[1][0], m[1][1])
    else:
        cy = math.atan2(-m[2][0], m[0][0]); cz = 0.0
    return [math.degrees(cx), math.degrees(cy), math.degrees(cz)]

def rounded(v, d=6): return [round(x, d) for x in v]

I3 = [[1.0,0,0],[0,1.0,0],[0,0,1.0]]

def find_dynamic_ancestor(bone, bone_map):
    """Return (dynamic_bone, between) where dynamic_bone is `bone` itself or the nearest
    ancestor registered in WHEEL_TRANSFORMS, and `between` are the bones strictly between
    the leaf `bone` and `dynamic_bone` (leaf-to-root order, excludes dynamic_bone).
    This lets non-registered child bones (e.g. static sub-parts riding on a turret) inherit
    the turret/barrel/wheel runtime transform instead of being baked static."""
    between = []
    cur = bone
    while cur is not None:
        if cur["name"] in WHEEL_TRANSFORMS:
            return cur, between
        between.append(cur)
        pn = cur.get("parent")
        cur = bone_map.get(pn) if pn else None
    return None, between


def cube_entry(cube, bone, bone_map):
    inflate = float(cube.get("inflate", 0.0))
    raw_o = [float(v) for v in cube["origin"]]
    raw_s = [float(v) for v in cube["size"]]
    eff_s = [raw_s[i] + 2.0*inflate for i in range(3)]
    # Library X-negation of origin: origin[0] = -(origin[0] + original_size[0])
    o = [-(raw_o[0] + raw_s[0]), raw_o[1], raw_o[2]]
    # Center uses ORIGINAL size (inflate doesn't affect center position)
    lc = [o[i] + raw_s[i]/2.0 for i in range(3)]
    # Half-extents use EFFECTIVE size (with inflate)
    he = [eff_s[i]/32.0 for i in range(3)]
    crot = [float(v) for v in cube.get("rotation", [0,0,0])]
    # Library X-negation of cube pivot
    cpiv = [float(v) for v in cube.get("pivot", bone.get("pivot", [0,0,0]))]
    cpiv[0] = -cpiv[0]
    cori = lib_matrix(crot) if any(crot) else I3
    if any(crot):
        local = [lc[i] - cpiv[i] for i in range(3)]
        rot = mat_vec(cori, local)
        lc = [rot[i] + cpiv[i] for i in range(3)]
    dyn_bone, between = find_dynamic_ancestor(bone, bone_map)
    if dyn_bone is not None:
        tn, part = WHEEL_TRANSFORMS[dyn_bone["name"]]
        combined = cori
        # bake rest-pose rotation of any bones strictly between the leaf and the dynamic bone
        for anc in between:
            arot = [float(v) for v in anc.get("rotation", [0, 0, 0])]
            if any(arot):
                apiv = [float(v) for v in anc.get("pivot", [0, 0, 0])]
                apiv[0] = -apiv[0]
                local = [lc[i] - apiv[i] for i in range(3)]
                rot = mat_vec(lib_matrix(arot), local)
                lc = [rot[i] + apiv[i] for i in range(3)]
                combined = mat_mul(lib_matrix(arot), combined)
        dbrot = [float(v) for v in dyn_bone.get("rotation", [0, 0, 0])]
        if dyn_bone["name"] in STEERING_WHEEL_BONES or dyn_bone["name"] in SKIP_ROTATION_BONES:
            dbrot = [0, 0, 0]
        if any(dbrot):
            dpiv = [float(v) for v in dyn_bone.get("pivot", [0, 0, 0])]
            dpiv[0] = -dpiv[0]
            local = [lc[i] - dpiv[i] for i in range(3)]
            rot = mat_vec(lib_matrix(dbrot), local)
            lc = [rot[i] + dpiv[i] for i in range(3)]
            combined = mat_mul(lib_matrix(dbrot), combined)
        orig_dpiv = [float(v) for v in dyn_bone.get("pivot", [0, 0, 0])]
        sbw_piv = [orig_dpiv[0]/16.0, orig_dpiv[1]/16.0, -orig_dpiv[2]/16.0]
        sbw_pos = [-lc[0]/16.0, lc[1]/16.0, -lc[2]/16.0]
        pos = [sbw_pos[i] - sbw_piv[i] for i in range(3)]
        m_sbw = sbw_conj(combined)
        euler = rounded(loader_euler(m_sbw))
        e = {"Part": part, "Transform": tn, "Rotation": tn, "Size": rounded(he), "Position": rounded(pos)}
        if any(abs(v) > 1e-5 for v in euler):
            e["CustomRotate"] = euler
        return e
    chain = []
    cur = bone
    while cur is not None:
        chain.append(cur)
        pn = cur.get("parent")
        cur = bone_map.get(pn) if pn else None
    combined = cori
    for anc in chain:
        arot = [float(v) for v in anc.get("rotation", [0,0,0])]
        if any(arot):
            apiv = [float(v) for v in anc.get("pivot", [0,0,0])]
            apiv[0] = -apiv[0]
            local = [lc[i] - apiv[i] for i in range(3)]
            rot = mat_vec(lib_matrix(arot), local)
            lc = [rot[i] + apiv[i] for i in range(3)]
            combined = mat_mul(lib_matrix(arot), combined)
    sbw_pos = [-lc[0]/16.0, lc[1]/16.0, -lc[2]/16.0]
    m_sbw = sbw_conj(combined)
    euler = rounded(loader_euler(m_sbw))
    e = {"Size": rounded(he), "Position": rounded(sbw_pos)}
    # Apply bone_transforms from config (sets Transform for body-branch bones)
    btf = config_bone_transforms.get(bone["name"])
    if btf:
        e["Transform"] = btf
        e["Rotation"] = btf
    # Auto-assign Part based on bone name (must match OBB.Part enum values)
    # Valid: Empty, WheelLeft, WheelRight, Turret, MainEngine, SubEngine, Body, Interactive, Collision
    if bone["name"] == "turret":
        e["Part"] = "Turret"
    elif bone["name"] == "barrel":
        e["Part"] = "Body"  # No "Barrel" in OBB.Part enum — use "Body"
    elif bone["name"] in ("RADAR", "radi"):
        e["Part"] = "MainEngine"
    if any(abs(v) > 1e-5 for v in euler):
        e["CustomRotate"] = euler
    return e


def needs_regeneration(model_path, output_path):
    """Check if model changed since last generation."""
    if not output_path.exists():
        return True
    return model_path.stat().st_mtime > output_path.stat().st_mtime


def generate_vehicle(name, config):
    """Generate OBB JSON for one vehicle from config."""
    global WHEEL_TRANSFORMS, STEERING_WHEEL_BONES, SKIP_BONES, SKIP_ROTATION_BONES

    model_rel = config["model"]
    output_rel = config["output"]
    model_path = PROJECT_DIR / "src" / "main" / "resources" / model_rel
    output_path = PROJECT_DIR / "src" / "main" / "resources" / output_rel

    if not model_path.exists():
        print(f"  ERROR: model not found: {model_path}")
        return False

    if not needs_regeneration(model_path, output_path):
        print(f"  SKIP (up-to-date): {name}")
        return False

    WHEEL_TRANSFORMS = {}
    for bone_name, (transform_name, part) in config.get("wheel_transforms", {}).items():
        WHEEL_TRANSFORMS[bone_name] = (transform_name, part)

    STEERING_WHEEL_BONES = set(config.get("steering_wheel_bones", []))
    SKIP_ROTATION_BONES = set(config.get("skip_rotation_bones", []))
    SKIP_BONES = set()

    global config_bone_transforms
    config_bone_transforms = config.get("bone_transforms", {})

    collision = config.get("collision", {"Size": [1, 1, 4], "Position": [0, 1, 0]})
    collision["Part"] = "Collision"

    geo = json.loads(model_path.read_text(encoding="utf-8"))
    bones = geo["minecraft:geometry"][0]["bones"]
    bm = {b["name"]: b for b in bones}

    def is_skipped(bone):
        """A bone is skipped if it, or ANY ancestor, is marked skip -- so decorative
        sub-parts nested under a skip bone (e.g. mirror/light detail bones) don't
        slip through just because their own name doesn't start with 'skip'."""
        cur = bone
        while cur is not None:
            if cur["name"].lower().startswith("skip") or cur["name"] in SKIP_BONES:
                return True
            pn = cur.get("parent")
            cur = bm.get(pn) if pn else None
        return False

    obbs = [collision]
    skip_count = 0
    for bone in bones:
        bname = bone["name"]
        if is_skipped(bone):
            skip_count += 1
            continue
        for cube in bone.get("cubes", []):
            # Skip cubes with name/comment matching "skip" or "skip{number}" (case-insensitive)
            cube_name = cube.get("name", cube.get("comment", ""))
            if isinstance(cube_name, str) and cube_name.lower().startswith("skip"):
                skip_count += 1
                continue
            # Skip cubes with custom "skip": true field
            if cube.get("skip", False):
                skip_count += 1
                continue
            try:
                obbs.append(cube_entry(cube, bone, bm))
            except Exception as exc:
                print(f"  WARN: cube in '{bname}': {exc}")

    vdata = dict(config.get("vehicle_data", {}))
    vdata["OBB"] = obbs

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(vdata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    parts = dict(Counter(o.get("Part", "Body") for o in obbs))
    print(f"  OK: {name} — {len(obbs)} OBB, parts={parts}, skipped={skip_count} bones")
    return True


def main():
    p = argparse.ArgumentParser(description="Generate OBB hitboxes from Bedrock models.")
    p.add_argument("--force", action="store_true", help="Regenerate even if up-to-date")
    p.add_argument("--vehicle", type=str, default="", help="Generate only this vehicle")
    args = p.parse_args()

    if not VEHICLES_JSON.exists():
        print(f"ERROR: config not found: {VEHICLES_JSON}")
        return

    configs = json.loads(VEHICLES_JSON.read_text(encoding="utf-8"))
    print(f"Vehicle configs: {len(configs)}")

    generated = 0
    for name, config in configs.items():
        if args.vehicle and name != args.vehicle:
            continue
        print(f"[{name}]")
        if args.force:
            # Force: touch the model file to trigger regeneration
            model_path = PROJECT_DIR / "src" / "main" / "resources" / config["model"]
            if model_path.exists():
                import time
                os.utime(model_path, (time.time(), time.time()))
        if generate_vehicle(name, config):
            generated += 1

    print(f"\nDone: {generated} regenerated, {len(configs) - generated} up-to-date")


if __name__ == "__main__":
    main()
