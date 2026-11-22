"""Execute stock and adapted current-firmware instructions; no device writes."""

import argparse
import io
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X19, UC_ARM64_REG_X20,
    UC_ARM64_REG_X21, UC_ARM64_REG_X22, UC_ARM64_REG_X23,
    UC_ARM64_REG_X24, UC_ARM64_REG_X25, UC_ARM64_REG_X26,
    UC_ARM64_REG_X27, UC_ARM64_REG_X28, UC_ARM64_REG_X29,
    UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC,
    UC_ARM64_REG_PSTATE, UC_ARM64_REG_W1,
)

from patch_modules import PROFILES, digest, patched_bytes


BASE, HEAP, STACK = 0x10000000, 0x20000000, 0x30000000
SAVED = (UC_ARM64_REG_X19, UC_ARM64_REG_X21, UC_ARM64_REG_X22,
         UC_ARM64_REG_X23, UC_ARM64_REG_X24, UC_ARM64_REG_X25,
         UC_ARM64_REG_X26, UC_ARM64_REG_X27, UC_ARM64_REG_X28,
         UC_ARM64_REG_X29, UC_ARM64_REG_X30, UC_ARM64_REG_SP)


def text_bytes(data):
    return ELFFile(io.BytesIO(data)).get_section_by_name(".text").data()


def machine(text):
    cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    cpu.mem_map(BASE, (len(text) + 4095) & ~4095)
    cpu.mem_write(BASE, text)
    cpu.mem_map(HEAP, 4096)
    cpu.mem_map(STACK, 4096)
    return cpu


def pd_select(cpu, local, remote, mf, role):
    data = bytearray(b"\xa5" * 512)
    struct.pack_into("<Q", data, 0, HEAP + 0x300)
    struct.pack_into("<I", data, 268, 0x9A if mf else 0x8A)
    cpu.mem_write(HEAP, bytes(data))
    cpu.reg_write(UC_ARM64_REG_PSTATE, 0x3C5)
    for i, register in enumerate(SAVED):
        cpu.reg_write(register, 0x12340000 + i * 16)
    cpu.reg_write(UC_ARM64_REG_X19, HEAP)
    cpu.reg_write(UC_ARM64_REG_X20, remote)
    cpu.reg_write(UC_ARM64_REG_X22, role)
    cpu.reg_write(UC_ARM64_REG_X23, local)
    before = {r: cpu.reg_read(r) for r in SAVED}
    # Failure paths stop before logging. No calls are stubbed or executed.
    cpu.emu_start(BASE + 0x187A0, BASE + 0x18874, count=50)
    pc = cpu.reg_read(UC_ARM64_REG_PC) - BASE
    assert pc in (0x187FC, 0x18874), hex(pc)
    after = bytearray(cpu.mem_read(HEAP, 512))
    if pc == 0x187FC:
        assert after == data
        assert all(cpu.reg_read(r) == v for r, v in before.items())
        return None
    a, b = struct.unpack_from("<II", after, 272)
    assert (a & 0xFF, b & 0xFF) == ((5, 6) if role == 1 else (6, 5))
    assert a >> 8 == b >> 8
    assert cpu.reg_read(UC_ARM64_REG_X0) == HEAP + 0x300
    after[272:280] = data[272:280]
    assert after == data, "Unexpected PD cache write"
    assert all(cpu.reg_read(r) == v for r, v in before.items())
    return a >> 8


def check_sections(original, result, spec, offset):
    assert original[:offset] == result[:offset]
    end = offset + len(spec["before"])
    assert original[end:] == result[end:]
    stock = ELFFile(io.BytesIO(original))
    trial = ELFFile(io.BytesIO(result))
    count = 0
    for a, b in zip(stock.iter_sections(), trial.iter_sections()):
        assert a.header == b.header and a.name == b.name
        if a.name != ".text":
            assert a.data() == b.data(), a.name
            count += 1
    restored = result[:offset] + spec["before"] + result[end:]
    assert restored == original, "Patch must be exactly reversible"
    return count


def verify(pd_path, drm_path):
    originals = {"pd-four-lane": pd_path.read_bytes(), "drm-hbr3": drm_path.read_bytes()}
    candidates, layouts = {}, {}
    refusals = 0
    for name, original in originals.items():
        result, offset = patched_bytes(original, name)
        candidates[name] = result
        sections = check_sections(original, result, PROFILES[name], offset)
        for invalid in (b"", original[:-1], original + b"\0", result):
            try:
                patched_bytes(invalid, name)
            except ValueError:
                refusals += 1
            else:
                raise AssertionError("Unsupported input accepted")
        layouts[name] = {"sha256": digest(result), "file_offset": hex(offset),
                         "unchanged_non_text_sections": sections,
                         "changed_bytes": sum(a != b for a, b in zip(original, result)),
                         "exact_reverse_passed": True}

    stock = machine(text_bytes(originals["pd-four-lane"]))
    trial = machine(text_bytes(candidates["pd-four-lane"]))
    for cpu in (stock, trial):
        cpu.hook_add(UC_HOOK_CODE, lambda uc, address, size, data: uc.emu_stop(),
                     begin=BASE + 0x187FC, end=BASE + 0x187FC)
    cases = 0
    for local in range(64):
        for remote in range(64):
            for mf in (False, True):
                for role in (1, 2):
                    common = local & remote & (0x3C if mf else 0x14)
                    expected_stock = next((p for p in (8, 32, 4, 16) if p & common), None)
                    expected_trial = next((p for p in (4, 16, 8, 32) if p & common), None)
                    assert pd_select(stock, local, remote, mf, role) == expected_stock
                    assert pd_select(trial, local, remote, mf, role) == expected_trial
                    cases += 1
    assert pd_select(trial, 0x1C, 0x0C, True, 1) == 4

    # Current parser passes a u32 in W1. Test its exact replaced instruction,
    # with untouched X0, SP, surrounding code, setter CALL26 and relocations.
    cpus = [machine(text_bytes(originals["drm-hbr3"])),
            machine(text_bytes(candidates["drm-hbr3"]))]
    for value in (0, 1, 2, 3, 0xFFFFFFFF):
        for i, cpu in enumerate(cpus):
            cpu.reg_write(UC_ARM64_REG_SP, STACK)
            cpu.reg_write(UC_ARM64_REG_X0, HEAP)
            cpu.mem_write(STACK + 12, struct.pack("<I", value))
            cpu.emu_start(BASE + 0x4A128, BASE + 0x4A12C, count=1)
            assert cpu.reg_read(UC_ARM64_REG_W1) == (3 if i else value)
            assert cpu.reg_read(UC_ARM64_REG_X0) == HEAP
            assert cpu.reg_read(UC_ARM64_REG_SP) == STACK

    return {"pd_matrix_cases": cases, "pd_stock_and_patched_runs": cases * 2,
            "pd_observed_pair_candidate": "C; local_config=0x405 remote_config=0x406",
            "pd_c_d_e_f_and_mf_and_both_roles": True,
            "pd_write_and_register_canaries": True,
            "hbr3_value_abi_cases": 5, "invalid_input_refusals": refusals,
            "modules": layouts, "device_module_loaded": False,
            "physical_high_refresh_verified": False,
            "scope": "Offline instruction execution; no PHY training or module loading proof."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pd", type=Path)
    parser.add_argument("drm", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    result = verify(args.pd, args.drm)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
