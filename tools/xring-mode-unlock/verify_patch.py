"""Exercise actual vendor AArch64 selection and patched return in Unicorn.

Only logging and the final setActiveConfigInternal call are stubbed. This
checks control flow, mode-map preservation, stack guard and callee saves;
it does not validate the vendor driver's hardware mode-switch behavior.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import re
import struct

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_W1, UC_ARM64_REG_X19, UC_ARM64_REG_X20,
    UC_ARM64_REG_X21, UC_ARM64_REG_X22, UC_ARM64_REG_X23, UC_ARM64_REG_X24,
    UC_ARM64_REG_X25, UC_ARM64_REG_X26, UC_ARM64_REG_X27, UC_ARM64_REG_X28,
    UC_ARM64_REG_X29, UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC,
    UC_ARM64_REG_TPIDR_EL0,
)

from patch_xdm import PATCH_OFFSET, PREFER_120, PREFER_144, SELECT_165_OFFSET, SELECT_165, patched_bytes

BASE, HEAP, STACK, TLS, STOP = 0x10000000, 0x20000000, 0x30000000, 0x40000000, 0x50000000
SAVED = [UC_ARM64_REG_X19, UC_ARM64_REG_X20, UC_ARM64_REG_X21,
         UC_ARM64_REG_X22, UC_ARM64_REG_X23, UC_ARM64_REG_X24,
         UC_ARM64_REG_X25, UC_ARM64_REG_X26, UC_ARM64_REG_X27,
         UC_ARM64_REG_X28, UC_ARM64_REG_X29]


def exercise(blob, modes, original=False, allow_no_call=False):
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    uc.mem_map(BASE, 0xE0000)
    uc.mem_write(BASE, blob[:0xE0000])
    for address, size in [(HEAP, 0x10000), (STACK, 0x10000), (TLS, 0x1000), (STOP, 0x1000)]:
        uc.mem_map(address, size)
    uc.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
    uc.mem_write(TLS + 40, struct.pack("<Q", 0x123456789ABCDEF0))
    uc.reg_write(UC_ARM64_REG_SP, STACK + 0xF000)
    uc.reg_write(UC_ARM64_REG_X30, STOP)
    initial = {}
    for reg in SAVED:
        initial[reg] = 0x60000000 + reg
        uc.reg_write(reg, initial[reg])
    uc.reg_write(UC_ARM64_REG_X0, HEAP)
    nodes = [HEAP + 0x1000 + i * 0x100 for i in range(len(modes))]
    uc.mem_write(HEAP + 672, struct.pack("<QQ", nodes[0] if nodes else 0, len(nodes)))
    for i, (key, width, height, fps) in enumerate(modes):
        node = nodes[i]
        uc.mem_write(node, struct.pack("<Q", nodes[i + 1] if i + 1 < len(nodes) else 0))
        uc.mem_write(node + 16, struct.pack("<i", key))
        uc.mem_write(node + 100, struct.pack("<H", width))
        uc.mem_write(node + 110, struct.pack("<H", height))
        uc.mem_write(node + 160, struct.pack("<f", fps))
    before = bytes(uc.mem_read(HEAP, 0x4000))
    calls = []
    selected = []

    def hook(cpu, address, size, unused):
        offset = address - BASE
        if original and offset == PATCH_OFFSET:
            selected.append(cpu.reg_read(UC_ARM64_REG_X24))
            cpu.emu_stop()
        elif offset == 0xD00D0:  # logTag; no driver access
            cpu.reg_write(UC_ARM64_REG_PC, cpu.reg_read(UC_ARM64_REG_X30))
        elif offset == 0xD0A50:
            assert cpu.reg_read(UC_ARM64_REG_X0) == HEAP
            calls.append(cpu.reg_read(UC_ARM64_REG_W1))
            cpu.reg_write(UC_ARM64_REG_X0, 0)
            cpu.reg_write(UC_ARM64_REG_PC, cpu.reg_read(UC_ARM64_REG_X30))
        elif 0xD0000 <= offset < 0xD3000:
            raise AssertionError(f"Unexpected native call {offset:#x}")

    uc.hook_add(UC_HOOK_CODE, hook)
    uc.emu_start(BASE + 0x7DACC, STOP, count=100000)
    assert bytes(uc.mem_read(HEAP, 0x4000)) == before, "Mode map changed"
    if original:
        assert len(selected) == 1
        return selected[0]
    assert uc.reg_read(UC_ARM64_REG_PC) == STOP, "Function did not return"
    assert uc.reg_read(UC_ARM64_REG_SP) == STACK + 0xF000, "Stack mismatch"
    assert all(uc.reg_read(reg) == value for reg, value in initial.items()), "Callee-save mismatch"
    assert len(calls) <= 1 if allow_no_call else len(calls) == 1
    return calls[0] if calls else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("kernel_modes", type=Path)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--prefer-120", action="store_true")
    group.add_argument("--prefer-144", action="store_true")
    group.add_argument("--prefer-165", action="store_true")
    args = parser.parse_args()
    blob = args.original.read_bytes()
    patched = patched_bytes(blob, prefer_120=args.prefer_120, prefer_144=args.prefer_144,
                            prefer_165=args.prefer_165)
    raw = args.kernel_modes.read_text().split("CONNECTOR id=1104")[0]
    modes = [(i, int(w), int(h), float(hz)) for i, (w, h, hz) in enumerate(
        re.findall(r"^\s+(\d+)x(\d+)@([\d.]+)", raw, re.M))]
    assert modes, "Expected captured kernel modes"
    if args.prefer_144:
        assert any(w == 2560 and h == 1440 and abs(hz - 144) < 0.5
                   for _, w, h, hz in modes), "Real 2K144 mode required"
    if args.prefer_165:
        assert any(w == 2560 and h == 1440 and abs(hz - 165) < 0.5
                   for _, w, h, hz in modes), "Real or EDID-derived 2K165 test input required"
    preferred = 165 if args.prefer_165 else 144 if args.prefer_144 else 120 if args.prefer_120 else None
    cases = [modes, list(reversed(modes)), [(73, 2560, 1440, 59.95055)],
             [(15, 1920, 1080, 120.0)],
             [(5, 2560, 1440, 60.0), (91, 1920, 1080, 120.0)],
             [(91, 1920, 1080, 120.0), (5, 2560, 1440, 60.0)]]
    rng = random.Random(17)
    for _ in range(32):
        copy = modes.copy()
        rng.shuffle(copy)
        cases.append(copy)
    if args.prefer_165:
        extra = [(47, 2560, 1440, 164.95774), (84, 2560, 1440, 179.9996),
                 (6, 1920, 1080, 165.0), (112, 3840, 2160, 165.0),
                 (2, 2560, 1440, 143.98062), (9, 2560, 1440, 59.95055)]
        cases.extend([[], extra, extra[1:], [(6, 1920, 1080, 165.0)],
                      [(7, 2560, 1440, 166.0)], [(9, 2560, 1440, 165.49)]])
        for _ in range(32):
            copy = extra.copy()
            rng.shuffle(copy)
            cases.append(copy)
    for i, case in enumerate(cases):
        if args.prefer_165:
            after_key = exercise(patched, case, allow_no_call=True)
            candidates = [m for m in case if m[1:3] == (2560, 1440)
                          and 0 < math.floor(m[3] + 0.5) <= 165]
            expected = max(candidates, key=lambda m: math.floor(m[3] + 0.5))[0] if candidates else None
            assert after_key == expected, (i, after_key, expected)
            continue
        before_key = exercise(blob, case, original=True)
        after_key = exercise(patched, case)
        candidates = [m for m in case if preferred and abs(m[3] - preferred) < 0.5]
        if preferred and candidates:
            expected = max(candidates, key=lambda m: (m[1] * m[2], round(m[3]), -abs(m[3] - round(m[3]))))
            assert after_key == expected[0], (i, after_key, expected)
        elif preferred:
            assert after_key == case[0][0], (i, after_key, case[0])
        else:
            assert before_key == after_key, (i, before_key, after_key)
    for bad in [b"", blob[:PATCH_OFFSET], bytes([blob[0] ^ 1]) + blob[1:]]:
        try:
            patched_bytes(bad, prefer_120=args.prefer_120, prefer_144=args.prefer_144,
                          prefer_165=args.prefer_165)
            raise AssertionError("Accepted an unsupported binary")
        except ValueError:
            pass
    assert len(blob) == len(patched)
    allowed = set(range(PATCH_OFFSET, PATCH_OFFSET + 16))
    if args.prefer_165:
        allowed.update(range(SELECT_165_OFFSET, SELECT_165_OFFSET + len(SELECT_165)))
    elif preferred:
        for offset in PREFER_144 if args.prefer_144 else PREFER_120:
            allowed.update(range(offset, offset + 4))
    assert all(i in allowed or a == b for i, (a, b) in enumerate(zip(blob, patched)))
    print(json.dumps({"instruction_cases_passed": len(cases), "binary_guard_cases_passed": 3,
                      "selection": "highest 2560x1440 up to 165 Hz; no lower resolution activation" if args.prefer_165 else f"existing {preferred} Hz preferred" if preferred else "original default key",
                      "preserved": ["mode map", "stack", "callee saves"],
                      "original_sha256": hashlib.sha256(blob).hexdigest(),
                      "patched_sha256": hashlib.sha256(patched).hexdigest(),
                      "device_switching_verified": False}, indent=2))


if __name__ == "__main__":
    main()
