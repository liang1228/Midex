"""Build a firmware-specific KernelSU preview ZIP from a verified stock ELF."""

import argparse
from dataclasses import replace
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import struct
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

from elftools.elf.elffile import ELFFile

from verify_preview import (
    EXTERNAL_DISPLAY, INTERNAL_DISPLAY, POLICY_ACCEPT, POLICY_REFUSE, POLICY_RETURN,
    SCHEDULED_POLICY_ACCEPT, SCHEDULED_POLICY_REFUSE, SCHEDULED_POLICY_RETURN,
    STOCK_SHA256, TYPE_RETURN, Request, exercise, exercise_policy, load_segments,
)


GUARD_ADDRESS = 0xCE6010
PATCH_SITES = (
    (TYPE_RETURN, bytes.fromhex("1f 04 00 71 a1 02 00 54"), "external_mode_guard"),
    (POLICY_RETURN, bytes.fromhex("c8 00 00 34"), "external_policy_guard"),
    (SCHEDULED_POLICY_RETURN, bytes.fromhex("c8 00 00 34"), "scheduled_external_policy_guard"),
)
LIBRARY_ENTRY = "system/system_ext/lib64/libsurfaceflinger.so"
MODULE_PROP = """id=extbridge_xuanyuan_sf_preview
name=ExtBridge Xiaomi 15 Ultra External 2K Refresh Preview
version=0.1.4
versionCode=5
author=ExtBridge
description=One boot preview for xuanyuan OS4.0.3.0.XOACNXM and the tested external monitor.
"""


def patch_library(stock, guard):
    if sha256(stock).hexdigest() != STOCK_SHA256:
        raise ValueError("unrecognized stock ELF for xuanyuan OS4.0.3.0.XOACNXM")
    elf = ELFFile(BytesIO(stock))
    guard_elf = ELFFile(BytesIO(guard))
    guard_section = guard_elf.get_section_by_name(".text")
    if guard_section is None or guard_section["sh_addr"] != GUARD_ADDRESS:
        raise ValueError("guard is not linked at the reserved virtual address")
    code = guard_section.data()
    if not code or len(code) > 0x200:
        raise ValueError("unexpected guard length")

    loads = [(i, segment) for i, segment in enumerate(elf.iter_segments())
             if segment["p_type"] == "PT_LOAD"]
    (index, executable), = [(i, segment) for i, segment in loads if segment["p_flags"] == 5]
    tls = next(elf.iter_segments(type="PT_TLS"))
    end = executable["p_offset"] + executable["p_filesz"]
    limit = min(segment["p_offset"] for _, segment in loads if segment["p_offset"] > end)
    new_end = GUARD_ADDRESS + len(code)
    if (executable["p_offset"] != executable["p_vaddr"]
            or executable["p_memsz"] != executable["p_filesz"]
            or GUARD_ADDRESS < tls["p_vaddr"] + tls["p_memsz"]
            or not end <= GUARD_ADDRESS < new_end <= limit
            or any(stock[end:new_end])):
        raise ValueError("guard would overlap existing ELF data")

    patched = bytearray(stock)
    symbols = guard_elf.get_section_by_name(".symtab")
    if symbols is None:
        raise ValueError("guard ELF requires a symbol table")
    for site, expected, name in PATCH_SITES:
        if stock[site:site + len(expected)] != expected:
            raise ValueError(f"stock conditional branch at {site:#x} does not match")
        matches = symbols.get_symbol_by_name(name) or []
        if len(matches) != 1:
            raise ValueError(f"expected one guard symbol named {name}")
        target = matches[0]["st_value"]
        displacement = (target - site) // 4
        if not GUARD_ADDRESS <= target < new_end or target % 4 or not 0 <= displacement < 1 << 25:
            raise ValueError(f"invalid branch target for {name}")
        struct.pack_into("<I", patched, site, 0x14000000 | displacement)
    patched[GUARD_ADDRESS:new_end] = code
    header = elf["e_phoff"] + index * elf["e_phentsize"]
    size = new_end - executable["p_offset"]
    struct.pack_into("<QQ", patched, header + 32, size, size)
    return bytes(patched)


def verify_policy_routes(original, overlay, *, scheduled=False):
    if scheduled:
        accept, refuse = SCHEDULED_POLICY_ACCEPT, SCHEDULED_POLICY_REFUSE
        path = "main-thread policy"
    else:
        accept, refuse = POLICY_ACCEPT, POLICY_REFUSE
        path = "caller-thread policy"
    cases = (
        ("stock external 2K100", original, {}, refuse),
        ("stock external 2K60", original, {"default_mode": 0}, refuse),
        ("target external mode 1", overlay, {}, accept),
        ("target external 2K60", overlay, {"default_mode": 0}, accept),
        ("target direct 2K165", overlay, {"default_mode": 2}, accept),
        ("target direct 2K144", overlay, {"default_mode": 3}, accept),
        ("target direct 2K120", overlay, {"default_mode": 4}, accept),
        ("target direct 2K100", overlay, {"default_mode": 5}, accept),
        ("internal stock route", overlay, {"connection": 0, "display": INTERNAL_DISPLAY,
                                           "default_mode": 7}, accept),
        ("no physical display record", overlay, {"found": False}, accept),
        ("other external display", overlay, {"display": EXTERNAL_DISPLAY + 1}, refuse),
        ("other connection type", overlay, {"connection": 2}, refuse),
        ("other external default mode", overlay, {"default_mode": 6}, refuse),
        ("negative external default mode", overlay, {"default_mode": -1}, refuse),
    )
    for name, loads, request, expected in cases:
        actual = exercise_policy(loads, scheduled=scheduled, **request)
        if actual != expected:
            raise AssertionError(f"{path}, {name}: expected {expected:#x}, observed {actual:#x}")
        print(f"PASS {path}, {name}: branch {expected:#x}")
    print(f"PASS {len(cases)} {path} branch cases (host only)")


def verify_mode_routes(original, overlay):
    target = Request(armed=None)
    submit, refuse = "mode controller", "stock external refusal"
    cases = (
        ("stock 2K100", original, target, refuse),
        ("stock 2K60", original, replace(target, sf_mode=0, hwc_config=0, hz=60), refuse),
        ("target 2K100", overlay, target, submit),
        ("target direct 2K180", overlay, replace(target, hz=180), submit),
        ("target direct 2K165", overlay, replace(target, sf_mode=2, hwc_config=2, hz=165), submit),
        ("target direct 2K144", overlay, replace(target, sf_mode=3, hwc_config=3, hz=144), submit),
        ("target direct 2K120", overlay, replace(target, sf_mode=4, hwc_config=4, hz=120), submit),
        ("target direct 2K100", overlay, replace(target, sf_mode=5, hwc_config=5, hz=100), submit),
        ("target 2K60 restore", overlay, replace(target, sf_mode=0, hwc_config=0, hz=60), submit),
        ("target 99.99 Hz encoding", overlay, replace(target, hz=99.99), submit),
        ("target 59.99 Hz encoding", overlay, replace(target, sf_mode=0, hwc_config=0, hz=59.99), submit),
        ("internal stock route", overlay, replace(target, display=INTERNAL_DISPLAY, connection=0), submit),
        ("other external", overlay, replace(target, display=target.display + 1), refuse),
        ("other width", overlay, replace(target, width=1921), refuse),
        ("other height", overlay, replace(target, height=1080), refuse),
        ("wrong HWC config", overlay, replace(target, hwc_config=7), refuse),
        ("wrong SF mode", overlay, replace(target, sf_mode=7), refuse),
        ("other enumerated mode", overlay, replace(target, sf_mode=7, hwc_config=7), refuse),
        ("negative mode", overlay, replace(target, sf_mode=-1, hwc_config=-1), refuse),
        ("wrong Hz", overlay, replace(target, hz=120), refuse),
        ("wrong direct rate for ID", overlay, replace(target, sf_mode=5, hwc_config=5, hz=180), refuse),
        ("out-of-tolerance Hz", overlay, replace(target, hz=100.03), refuse),
        ("NaN Hz", overlay, replace(target, hz=float("nan")), refuse),
        ("infinite Hz", overlay, replace(target, hz=float("inf")), refuse),
        ("stock vendor veto", overlay, replace(target, vendor_reject=True), "stock vendor guard"),
        ("stock null request", overlay, replace(target, has_mode=False), "stock null refusal"),
    )
    for name, loads, request, expected in cases:
        actual = exercise(loads, request, simulate_preview=False)
        if actual != [expected]:
            raise AssertionError(f"{name}: expected {[expected]}, observed {actual}")
        print(f"PASS {name}: {expected}")
    print(f"PASS {len(cases)} cases with the actual packaged ARM64 guard (host only)")


def write_module(patched, output):
    module_dir = Path(__file__).with_name("module")
    with ZipFile(output, "x") as archive:
        def write_entry(name, data, mode=0o644):
            info = ZipInfo(name)
            info.create_system = 3
            info.external_attr = (0o100000 | mode) << 16
            archive.writestr(info, data, compress_type=ZIP_DEFLATED)

        write_entry("module.prop", MODULE_PROP)
        for name in ("customize.sh", "post-fs-data.sh", "post-mount.sh", "late-load.sh"):
            write_entry(name, (module_dir / name).read_text(encoding="ascii"), 0o755)
        write_entry(LIBRARY_ENTRY, patched)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock_elf", type=Path)
    parser.add_argument("guard_elf", type=Path)
    parser.add_argument("output_zip", type=Path)
    args = parser.parse_args()
    if args.output_zip.exists():
        parser.error("output already exists")
    stock = args.stock_elf.read_bytes()
    try:
        patched = patch_library(stock, args.guard_elf.read_bytes())
    except ValueError as error:
        parser.error(str(error))
    original, overlay = tuple(load_segments(stock)), tuple(load_segments(patched))
    verify_policy_routes(original, overlay)
    verify_policy_routes(original, overlay, scheduled=True)
    verify_mode_routes(original, overlay)
    write_module(patched, args.output_zip)
    with ZipFile(args.output_zip) as archive:
        if archive.testzip() or archive.read(LIBRARY_ENTRY) != patched:
            raise AssertionError("module archive verification failed")
    print("Patched ELF SHA-256", sha256(patched).hexdigest())
    print("Module ZIP SHA-256", sha256(args.output_zip.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
