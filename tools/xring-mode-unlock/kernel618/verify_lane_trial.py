"""Verify exact-build ABI and execute the compiled lane-selection callback."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X8,
    UC_ARM64_REG_X9, UC_ARM64_REG_X10, UC_ARM64_REG_X11, UC_ARM64_REG_X12,
    UC_ARM64_REG_X19, UC_ARM64_REG_X30, UC_ARM64_REG_PC,
)

from patch_modules import PD_SHA
from verify import BASE, HEAP, machine, pd_select, text_bytes
from verify_observer_abi import btf_structs, cfi, versions


def compiled_callback(module, stock, callback_name="select_pin", point=0x187C0):
    """Relocate and execute actual module instructions, inside stock PD code."""
    syms = module.get_section_by_name(".symtab")
    callback = syms.get_symbol_by_name(callback_name)[0]
    data = bytearray(module.get_section_by_name(".text").data())
    sections = {i: 0x40000000 + i * 0x10000 for i, s in enumerate(module.iter_sections())
                if s.name in (".text", ".data", ".bss")}
    text_base = sections[callback["st_shndx"]]
    for r in module.get_section_by_name(".rela.text").iter_relocations():
        pos = r["r_offset"]
        if not callback["st_value"] <= pos < callback["st_value"] + callback["st_size"]:
            continue
        s = syms.get_symbol(r["r_info_sym"])
        target = sections[s["st_shndx"]] + s["st_value"] + r["r_addend"]
        instruction = struct.unpack_from("<I", data, pos)[0]
        kind = r["r_info_type"]
        if kind == 275:  # R_AARCH64_ADR_PREL_PG_HI21
            delta = ((target & ~4095) - ((text_base + pos) & ~4095)) >> 12
            instruction = (instruction & ~0x60FFFFE0) | ((delta & 3) << 29) | (((delta >> 2) & 0x7FFFF) << 5)
        else:
            scale = {278: 0, 285: 2, 286: 3}[kind]
            instruction = (instruction & ~0x3FFC00) | (((target & 4095) >> scale) << 10)
        struct.pack_into("<I", data, pos, instruction)
    cpu = machine(stock)
    for index, address in sections.items():
        sec = module.get_section(index)
        cpu.mem_map(address, (sec["sh_size"] + 4095) & ~4095)
        if sec["sh_type"] != "SHT_NOBITS":
            cpu.mem_write(address, bytes(data) if sec.name == ".text" else sec.data())
    regs, probe, stop = 0x51000000, 0x51001000, 0x52000000
    cpu.mem_map(regs, 8192)

    def address(name):
        s = syms.get_symbol_by_name(name)[0]
        return sections[s["st_shndx"]] + s["st_value"]

    cpu.mem_write(address("pd"), struct.pack("<Q", HEAP))
    cpu.mem_write(probe + 40, struct.pack("<Q", BASE + point))

    def invoke(uc, pc, size, _):
        saved = {r: uc.reg_read(r) for r in (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X30)}
        uc.mem_write(regs, b"\0" * 320)
        uc.mem_write(regs + 64, struct.pack("<Q", uc.reg_read(UC_ARM64_REG_X8)))
        uc.mem_write(regs + 88, struct.pack("<Q", uc.reg_read(UC_ARM64_REG_X11)))
        uc.mem_write(regs + 96, struct.pack("<Q", uc.reg_read(UC_ARM64_REG_X12)))
        uc.mem_write(regs + 152, struct.pack("<Q", uc.reg_read(UC_ARM64_REG_X19)))
        uc.mem_write(regs + 256, struct.pack("<Q", pc))
        # Unicorn does not allow nested emu_start. Execute callback in a second
        # CPU with the same relocated module memory and transfer only pt_regs.
        other = invoke.callback_cpu
        for index, address_ in sections.items():
            size_ = module.get_section(index)["sh_size"]
            other.mem_write(address_, bytes(uc.mem_read(address_, size_)))
        other.mem_write(regs, bytes(uc.mem_read(regs, 8192)))
        other.reg_write(UC_ARM64_REG_X0, probe)
        other.reg_write(UC_ARM64_REG_X1, regs)
        other.reg_write(UC_ARM64_REG_X30, stop)
        other.emu_start(text_base + callback["st_value"], stop, count=100)
        result = other.reg_read(UC_ARM64_REG_X0)
        assert result in (0, 1)
        for index, address_ in sections.items():
            size_ = module.get_section(index)["sh_size"]
            uc.mem_write(address_, bytes(other.mem_read(address_, size_)))
        for offset, register in ((64, UC_ARM64_REG_X8), (88, UC_ARM64_REG_X11), (96, UC_ARM64_REG_X12)):
            uc.reg_write(register, struct.unpack("<Q", other.mem_read(regs + offset, 8))[0])
        if result:
            uc.reg_write(UC_ARM64_REG_PC, struct.unpack("<Q", other.mem_read(regs + 256, 8))[0])
        assert all(uc.reg_read(r) == v for r, v in saved.items())

    other = machine(stock)
    for index, address_ in sections.items():
        size_ = module.get_section(index)["sh_size"]
        other.mem_map(address_, (size_ + 4095) & ~4095)
    other.mem_map(regs, 8192)
    invoke.callback_cpu = other
    cpu.hook_add(UC_HOOK_CODE, invoke, begin=BASE + point, end=BASE + point)
    cpu.hook_add(UC_HOOK_CODE, lambda uc, *args: uc.emu_stop(),
                 begin=BASE + 0x187FC, end=BASE + 0x187FC)
    return cpu, address("select_c" if callback_name == "select_pin" else "high_rate")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path)
    parser.add_argument("--module", default="extbridge_618_lane_trial-v6.ko")
    parser.add_argument("--report", default="lane-trial-v6-abi.json")
    args = parser.parse_args()
    root = args.project
    out = root / "outputs/kernel618-adaptation-20261117"
    raw = (out / args.module).read_bytes()
    module = ELFFile(io.BytesIO(raw))
    current_data = (root / "outputs/external-2k-hbr3-20261117/external_tcpc-current.ko").read_bytes()
    assert hashlib.sha256(current_data).hexdigest() == PD_SHA
    current = ELFFile(io.BytesIO(current_data))
    kernel = ELFFile(io.BytesIO((root / "references/gki-android17-6.18-15549627/vmlinux").read_bytes()))
    a = btf_structs(kernel.get_section_by_name(".BTF").data())
    b = btf_structs((root / "outputs/external-2k-hbr3-20261117/running-kernel.btf").read_bytes())
    sizes = {}
    for name in ("module", "kprobe", "pt_regs", "mutex", "device", "work_struct", "delayed_work", "kernel_param_ops"):
        assert a[name] == b[name], name
        sizes[name] = a[name]["size"]
    expected = {r[1]: int(r[0], 16) for r in (l.split() for l in
                (root / "references/gki-android17-6.18-15549627/kernel_aarch64_Module.symvers").read_text().splitlines())}
    crc_symbol = current.get_section_by_name(".symtab").get_symbol_by_name("__crc_tcpc_dev_get_by_name")[0]
    vendor_crc = struct.unpack_from("<I", current.get_section(crc_symbol["st_shndx"]).data(), crc_symbol["st_value"])[0]
    assert vendor_crc == 0x857D345B
    expected["tcpc_dev_get_by_name"] = vendor_crc
    status_crc = current.get_section_by_name(".symtab").get_symbol_by_name("__crc_tcpm_dpm_dp_status_update")[0]
    expected["tcpm_dpm_dp_status_update"] = struct.unpack_from("<I", current.get_section(status_crc["st_shndx"]).data(), status_crc["st_value"])[0]
    imported = versions(module)
    assert all(expected[n] == v for n, v in imported.items())
    cfi_pairs = {"select_pin": "aggr_pre_handler", "select_rate": "aggr_pre_handler", "select_ssc": "aggr_pre_handler", "observe_ack": "aggr_pre_handler",
                 "begin": "vmstat_shepherd", "restore": "vmstat_shepherd",
                 "start_trial": "param_set_bool", "recover_link": "param_set_bool", "read_status": "param_get_int"}
    hashes = {}
    for name, original in cfi_pairs.items():
        assert cfi(module, name) == cfi(kernel, original), name
        hashes[name] = hex(cfi(module, name))
    assert cfi(module, "signature_proof") == cfi(current, "dp_dfp_u_request_dp_configuration") == 0x9246B1A3
    hashes["vendor_request_signature"] = hex(cfi(module, "signature_proof"))
    assert cfi(module, "ack_signature_proof") == cfi(current, "tcpci_dp_notify_config_done") == 0x1942C38B
    hashes["vendor_ack_signature"] = hex(cfi(module, "ack_signature_proof"))
    assert cfi(module, "init_module") == cfi(current, "init_module")
    assert cfi(module, "cleanup_module") == cfi(current, "cleanup_module")

    cpu, flag = compiled_callback(module, text_bytes(current_data))
    cases = 0
    for enabled in (False, True):
        cpu.mem_write(flag, bytes([enabled]))
        for local in range(64):
            for remote in range(64):
                for mf in (False, True):
                    for role in (1, 2):
                        common = local & remote & (0x3C if mf else 0x14)
                        order = (4, 8, 32, 16) if enabled else (8, 32, 4, 16)
                        wanted = next((p for p in order if p & common), None)
                        assert pd_select(cpu, local, remote, mf, role) == wanted
                        cases += 1
    drm_raw = (root / "outputs/xring-drm-current.ko").read_bytes()
    assert hashlib.sha256(drm_raw).hexdigest() == "7a0014feaf4868edcfef31dec9c6f68bce21ccb616179ed5a2812c204b2b1913"
    drm_text = text_bytes(drm_raw)
    assert struct.unpack_from("<I", drm_text, 0x341A0)[0] == 0x6B0C017F
    cpu, flag = compiled_callback(module, drm_text, "select_rate", 0x341A0)
    rate_cases = 0
    for enabled in (False, True):
        cpu.mem_write(flag, bytes([enabled]))
        for initial in range(5):
            for sink in range(5):
                memory = bytearray(b"\xa5" * 512)
                cpu.mem_write(HEAP, bytes(memory))
                cpu.reg_write(UC_ARM64_REG_X19, HEAP)
                cpu.reg_write(UC_ARM64_REG_X8, 2)
                cpu.reg_write(UC_ARM64_REG_X11, initial)
                cpu.reg_write(UC_ARM64_REG_X12, sink)
                cpu.emu_start(BASE + 0x341A0, BASE + 0x341AC, count=10)
                source = 3 if enabled and initial == 2 and sink == 3 else initial
                assert cpu.reg_read(UC_ARM64_REG_X11) == source
                assert cpu.reg_read(UC_ARM64_REG_X12) == sink
                assert struct.unpack("<II", cpu.mem_read(HEAP + 64, 8)) == (min(source, sink), 2)
                after = bytearray(cpu.mem_read(HEAP, 512))
                after[64:72] = memory[64:72]
                assert after == memory
                rate_cases += 1
    assert struct.unpack_from("<I", drm_text, 0x3413C)[0] == 0x0A0A0169
    cpu, flag = compiled_callback(module, drm_text, "select_ssc", 0x3413C)
    symbol = module.get_section_by_name(".symtab").get_symbol_by_name("nossc")[0]
    nossc_address = 0x40000000 + symbol["st_shndx"] * 0x10000 + symbol["st_value"]
    ssc_cases = 0
    for enabled in (False, True):
        cpu.mem_write(flag, bytes([enabled]))
        for nossc in (False, True):
            cpu.mem_write(nossc_address, bytes([nossc]))
            for cap in (0, 1):
                for initial in (0, 1):
                    cpu.reg_write(UC_ARM64_REG_X10, cap)
                    cpu.reg_write(UC_ARM64_REG_X11, initial)
                    cpu.emu_start(BASE + 0x3413C, BASE + 0x34140, count=5)
                    expected_ssc = 0 if enabled and nossc else initial
                    assert cpu.reg_read(UC_ARM64_REG_X9) == cap & expected_ssc
                    assert cpu.reg_read(UC_ARM64_REG_X10) == cap
                    ssc_cases += 1
    result = {"module_sha256": hashlib.sha256(raw).hexdigest(), "btf_identical_layouts": sizes,
              "import_crcs_match": len(imported), "vendor_crc_from_current_binary": hex(vendor_crc),
              "kcfi_hashes_match": hashes, "compiled_callback_with_stock_instruction_cases": cases,
              "compiled_rate_callback_with_stock_min_cases": rate_cases,
              "compiled_ssc_callback_with_stock_mask_cases": ssc_cases,
              "write_and_register_canaries": True, "scope": "Offline ABI/instruction proof. Device trial is separate."}
    (out / args.report).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    main()
