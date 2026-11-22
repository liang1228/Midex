"""Generate a revision-specific XRING mode-table trial; never install it.

By default the original selector remains intact. The explicit --prefer-120
trial changes its 50/60 Hz comparisons to 120 Hz. Instead of erasing the mode
map and renumbering its winner to zero, activate the winner by its original key.
Only the exact library examined on violin / OS4.0.6.0.XOTCNXM is accepted.
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct


ORIGINAL_SHA256 = "bde2d398144ba20fd0772f48add5e0787fedb8bc27b13f85721f430efff77e85"
PATCH_OFFSET = 0x7DE7C
ORIGINAL = bytes.fromhex("00e4006f684e41f9e00306ade02f803d")


def branch(source, target, link=False):
    delta = target - source
    if delta % 4 or not -(1 << 27) <= delta < (1 << 27):
        raise ValueError("Invalid AArch64 branch")
    return (0x94000000 if link else 0x14000000) | ((delta // 4) & 0x3FFFFFF)


PATCH = struct.pack(
    "<4I",
    0xAA1303E0,  # mov x0, x19: this
    0x2A1803E1,  # mov w1, w24: original selected config key
    branch(0x7DE84, 0xD0A50, link=True),  # setActiveConfigInternal@plt
    branch(0x7DE88, 0x7E1C0),  # stack guard and complete original epilogue
)


PREFER_120 = {
    0x7DB1C: (0x52800649, 0x52800F09),  # first node: mov w9, #50 -> #120
    0x7DB40: (0x7100F29F, 0x7101E29F),  # first node: cmp w20, #60 -> #120
    0x7DC0C: (0x7100F2DF, 0x7101E2DF),  # candidate: cmp w22, #60 -> #120
    0x7DC14: (0x7100CADF, 0x7101E2DF),  # candidate: cmp w22, #50 -> #120
}

PREFER_144 = {
    0x7DB1C: (0x52800649, 0x52801209),
    0x7DB40: (0x7100F29F, 0x7102429F),
    0x7DC0C: (0x7100F2DF, 0x710242DF),
    0x7DC14: (0x7100CADF, 0x710242DF),
}

SELECT_165_OFFSET = 0x7DB04
# Assembled from select_2k_165.S. Select only 2560x1440, highest rounded Hz <=165.
SELECT_165 = bytes.fromhex(
    "685241f91500805218008052280200b409c940793f01287181010054"
    "09dd40793f8116712101005400a140bd0900251e3f950271a8000054"
    "3f01156b69000054181140b9f503092a080140f9f0ffff17"
)
SELECT_165 += struct.pack(
    "<2I",
    0x34000015 | (((0x7E1C0 - (SELECT_165_OFFSET + len(SELECT_165))) // 4) << 5),
    branch(SELECT_165_OFFSET + len(SELECT_165) + 4, PATCH_OFFSET),
)


def patched_bytes(original, prefer_120=False, prefer_144=False, prefer_165=False):
    if sum((prefer_120, prefer_144, prefer_165)) > 1:
        raise ValueError("Only one refresh preference is allowed")
    if hashlib.sha256(original).hexdigest() != ORIGINAL_SHA256:
        raise ValueError("Unsupported libxdmcore.so SHA-256; no patch generated")
    if original[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", original, 18)[0] != 183:
        raise ValueError("Expected little-endian ELF64 AArch64")
    if original[PATCH_OFFSET:PATCH_OFFSET + len(ORIGINAL)] != ORIGINAL:
        raise ValueError("Unexpected original instructions")
    result = bytearray(original[:PATCH_OFFSET] + PATCH + original[PATCH_OFFSET + len(PATCH):])
    preference = PREFER_144 if prefer_144 else PREFER_120 if prefer_120 else {}
    if preference:
        for offset, (before, after) in preference.items():
            if struct.unpack_from("<I", original, offset)[0] != before:
                raise ValueError("Unexpected preference comparison")
            struct.pack_into("<I", result, offset, after)
    if prefer_165:
        result[SELECT_165_OFFSET:SELECT_165_OFFSET + len(SELECT_165)] = SELECT_165
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("output", type=Path)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--prefer-120", action="store_true",
                        help="Temporary trial only: prefer existing 120 Hz modes at initialization")
    group.add_argument("--prefer-144", action="store_true",
                       help="Temporary DP trial only: prefer existing 144 Hz modes at initialization")
    group.add_argument("--prefer-165", action="store_true",
                       help="Temporary DP trial only: prefer existing 165 Hz modes at initialization")
    args = parser.parse_args()
    if args.original.resolve() == args.output.resolve():
        parser.error("Output must differ from original")
    result = patched_bytes(args.original.read_bytes(), prefer_120=args.prefer_120,
                           prefer_144=args.prefer_144, prefer_165=args.prefer_165)
    with args.output.open("xb") as target:
        target.write(result)
    manifest = {
        "device": "violin", "rom": "OS4.0.6.0.XOTCNXM",
        "original_sha256": ORIGINAL_SHA256,
        "patched_sha256": hashlib.sha256(result).hexdigest(),
        "offset": hex(PATCH_OFFSET), "original": ORIGINAL.hex(), "patch": PATCH.hex(),
        "scope": "preserve kernel-derived mode table; " + ("highest real 2560x1440 mode up to 165 Hz; skip activation without 2K" if args.prefer_165 else "prefer real 144 Hz at initialization" if args.prefer_144 else "prefer real 120 Hz at initialization" if args.prefer_120 else "keep original default selector"),
        "preference_comparisons": {hex(k): [hex(v[0]), hex(v[1])] for k, v in (PREFER_144 if args.prefer_144 else PREFER_120 if args.prefer_120 else {}).items()},
        "selector": {"offset": hex(SELECT_165_OFFSET), "patch": SELECT_165.hex()} if args.prefer_165 else None,
        "status": "experimental; not installed; hardware switching unverified",
    }
    args.output.with_suffix(".json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
