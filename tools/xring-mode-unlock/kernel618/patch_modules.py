"""Create offline patches for the exact violin Android 17 / 6.18 modules.

Never loads modules or writes partitions. The PD patch changes pin selection,
leaving the original PD Configure / USB mux / PHY / DP training path intact.
The independent HBR3 profile changes the current value argument, not the old
6.6 pointer ABI. Source basis and deployment limits are in README.md.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile


PD_SHA = "78a767af8dea2e4f6f3c3d7c6e9a93e0e3fb46eede1df1888ce533bf70e294b9"
DRM_SHA = "7a0014feaf4868edcfef31dec9c6f68bce21ccb616179ed5a2812c204b2b1913"
VERMAGIC = ("6.18.21-android17-5-maybe-dirty-4k SMP preempt "
            "mod_unload modversions aarch64")


def branch(source, target):
    delta = target - source
    if delta % 4 or not -(1 << 27) <= delta < (1 << 27):
        raise ValueError("Invalid AArch64 branch")
    return 0x14000000 | ((delta // 4) & 0x3FFFFFF)


def tbnz(source, target, bit, register=8):
    delta = target - source
    if delta % 4 or not -(1 << 15) <= delta < (1 << 15):
        raise ValueError("Invalid AArch64 TBNZ")
    if not 0 <= bit < 32 or not 0 <= register < 32:
        raise ValueError("Expected a W register and bit below 32")
    return 0x37000000 | (bit << 19) | (((delta // 4) & 0x3FFF) << 5) | register


PD_BEFORE = bytes.fromhex(
    "e803183708042837280410378a0080521f011c7288008252"
    "4921092a2801881a1c000014")
PD_AFTER = struct.pack("<9I",
    tbnz(0x187C0, 0x1884C, 2),  # C: existing 0x404 config block
    tbnz(0x187C4, 0x187D8, 4),  # E: 0x1004 config block below
    tbnz(0x187C8, 0x1883C, 3),  # D: existing 0x804 config block
    tbnz(0x187CC, 0x18844, 5),  # F: existing 0x2004 config block
    branch(0x187D0, 0x187FC),    # no standard C/D/E/F pin: original error path
    0xD503201F,
    0x52820088,                 # mov w8, #0x1004 (E)
    branch(0x187DC, 0x18850),    # original role bits, stores, PD notification
    0xD503201F,
)

PROFILES = {
    "pd-four-lane": {
        "sha256": PD_SHA, "name": "external_tcpc",
        "function": "dp_dfp_u_request_dp_configuration", "start": 0x186C8,
        "size": 0x234, "offset": 0x187C0,
        "before": PD_BEFORE, "after": PD_AFTER,
        "behavior": "Prefer common C/E four-lane pins, then D/F; retain MF filtering and real PD Configure path.",
    },
    "drm-hbr3": {
        "sha256": DRM_SHA, "name": "xring_drm",
        "function": "dp_parser_phy_param_parse", "start": 0x4A0D4,
        "size": 0x8BC, "offset": 0x4A128,
        "before": struct.pack("<I", 0xB9400FE1),
        "after": struct.pack("<I", 0x52800061),  # mov w1, #3; value ABI
        "behavior": "Raise parsed max-link-rate to HBR3 through the original u32 setter; retain training fallback.",
    },
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def patched_bytes(original, profile):
    spec = PROFILES[profile]
    if digest(original) != spec["sha256"]:
        raise ValueError("Unsupported original module SHA-256")
    elf = ELFFile(io.BytesIO(original))
    if (elf.elfclass, elf.little_endian, elf["e_machine"], elf["e_type"]) != (
            64, True, "EM_AARCH64", "ET_REL"):
        raise ValueError("Expected an ELF64 AArch64 relocatable module")
    info = elf.get_section_by_name(".modinfo").data().split(b"\0")
    for entry in ("name=" + spec["name"], "vermagic=" + VERMAGIC):
        if entry.encode() not in info:
            raise ValueError("Unexpected module metadata")
    symbol = elf.get_section_by_name(".symtab").get_symbol_by_name(spec["function"])[0]
    text = elf.get_section_by_name(".text")
    if (symbol["st_value"], symbol["st_size"]) != (spec["start"], spec["size"]):
        raise ValueError("Unexpected current-firmware function layout")
    if elf.get_section(symbol["st_shndx"]).name != ".text":
        raise ValueError("Unexpected function section")
    offset = text["sh_offset"] + spec["offset"]
    before, after = spec["before"], spec["after"]
    if len(before) != len(after) or original[offset:offset + len(before)] != before:
        raise ValueError("Unexpected original instructions")
    relocs = elf.get_section_by_name(".rela.text")
    if any(spec["offset"] <= r["r_offset"] < spec["offset"] + len(before)
           for r in relocs.iter_relocations()):
        raise ValueError("Patch overlaps a relocation")
    result = original[:offset] + after + original[offset + len(before):]
    # No section layout, modversions, CFI type identifiers, entries or ABI changes.
    assert len(result) == len(original)
    return result, offset


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=PROFILES)
    parser.add_argument("original", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.original.resolve() == args.output.resolve():
        parser.error("Output must differ from original")
    metadata = args.output.with_suffix(".json")
    if args.output.exists() or metadata.exists():
        parser.error("Output or manifest already exists")
    original = args.original.read_bytes()
    result, offset = patched_bytes(original, args.profile)
    spec = PROFILES[args.profile]
    report = {
        "profile": args.profile, "original_sha256": digest(original),
        "patched_sha256": digest(result), "module_name": spec["name"],
        "vermagic_unchanged": VERMAGIC, "file_offset": hex(offset),
        "text_offset": hex(spec["offset"]), "before": spec["before"].hex(),
        "after": spec["after"].hex(), "behavior": spec["behavior"],
        "status": "offline candidate; not loaded; physical link and refresh unverified",
        "deployment": "Same module identity as stock. Do not insmod alongside stock, force-unload dependencies, or flash directly.",
    }
    with args.output.open("xb") as target:
        target.write(result)
    with metadata.open("x", encoding="utf-8") as target:
        target.write(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
