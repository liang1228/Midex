"""Exercise the stock xuanyuan SurfaceFlinger branch in a host-only ARM64 VM."""

import argparse
from dataclasses import dataclass, replace
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UcError, UC_ARCH_ARM64, UC_HOOK_CODE, UC_MODE_ARM
from unicorn.arm64_const import (
    UC_ARM64_REG_LR, UC_ARM64_REG_NZCV, UC_ARM64_REG_PC, UC_ARM64_REG_SP,
    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X21, UC_ARM64_REG_X25,
    UC_ARM64_REG_X28,
)


STOCK_SHA256 = "fe89bbd525f33fb806d532135458d090c88798f50ddfc6368bd09212a1bd03d6"
EXTERNAL_DISPLAY = 4639175559211316245
INTERNAL_DISPLAY = 4630946696124728723
FUNCTION = 0x4FECD0
TYPE_RETURN = 0x4FEE18
SUBMIT = 0x4FEEAC
POLICY_ENTRY = 0x529F00
POLICY_RETURN = 0x529F08
POLICY_REFUSE = 0x529F0C
POLICY_ACCEPT = 0x529F20
SCHEDULED_POLICY_ENTRY = 0x56D5C0
SCHEDULED_POLICY_RETURN = 0x56D5C8
SCHEDULED_POLICY_REFUSE = 0x56D5CC
SCHEDULED_POLICY_ACCEPT = 0x56D5E0
MEMORY = 0x20000000
SF, REQUEST, MODE = (MEMORY + offset for offset in (0, 0x3000, 0x4000))
HW, VTABLE, STUB = (MEMORY + offset for offset in (0x5000, 0x6000, 0x7000))
STACK = MEMORY + 0x9000


@dataclass(frozen=True)
class Request:
    display: int = EXTERNAL_DISPLAY
    sf_mode: int = 1
    hwc_config: int = 1
    width: int = 2560
    height: int = 1440
    hz: float = 100.0
    connection: int = 1
    armed: int | None = 1
    main_thread: bool = True
    lifetime_ms: int = 9000
    vendor_reject: bool = False
    has_mode: bool = True


def connection_for_preview(vm, request, return_address, display_id):
    """Model the scoped decision at the return from the connection-type query."""
    if (request.connection != 1 or not request.main_thread
            or return_address != TYPE_RETURN or display_id != EXTERNAL_DISPLAY
            or request.armed not in (0, 1) or request.lifetime_ms <= 0):
        return request.connection

    try:
        request_ptr = vm.reg_read(UC_ARM64_REG_X21)
        mode_ptr, = struct.unpack("<Q", vm.mem_read(request_ptr + 0x10, 8))
        if not mode_ptr:
            return request.connection
        hwc_config, sf_mode, physical_id, width, height, hz = struct.unpack(
            "<iiQiif", vm.mem_read(mode_ptr, 28)
        )
    except UcError:
        return request.connection

    target_hz = 100 if request.armed == 1 else 60
    if (hwc_config == sf_mode == request.armed and physical_id == display_id
            and (width, height) == (2560, 1440) and abs(hz - target_hz) < 0.02):
        return 0
    return request.connection


def create_vm(loads):
    vm = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    for base, size, data in loads:
        vm.mem_map(base, size)
        vm.mem_write(base, data)
    vm.mem_map(MEMORY, 0xA000)
    return vm


def exercise_policy(loads, *, display=EXTERNAL_DISPLAY, default_mode=1, connection=1,
                    found=True, scheduled=False):
    if scheduled:
        entry, accept, refuse = (
            SCHEDULED_POLICY_ENTRY, SCHEDULED_POLICY_ACCEPT, SCHEDULED_POLICY_REFUSE
        )
        spec_register, spec_offset = UC_ARM64_REG_X28, 0x98
    else:
        entry, accept, refuse = POLICY_ENTRY, POLICY_ACCEPT, POLICY_REFUSE
        spec_register, spec_offset = UC_ARM64_REG_X25, 0x90

    vm = create_vm(loads)
    vm.mem_write(SF, struct.pack("<Q20xI", display, connection))
    vm.mem_write(REQUEST, struct.pack("<i", default_mode))
    registers = {
        UC_ARM64_REG_X0: SF if found else 0,
        UC_ARM64_REG_X1: int(found),
        spec_register: REQUEST + spec_offset,
        UC_ARM64_REG_NZCV: 0xA0000000,
    }
    for register, value in registers.items():
        vm.reg_write(register, value)

    vm.hook_add(UC_HOOK_CODE, lambda uc, _address, _size, _data: uc.emu_stop(),
                begin=refuse, end=refuse)
    vm.emu_start(entry, accept, count=50)

    for register, value in registers.items():
        if vm.reg_read(register) != value:
            raise AssertionError(f"policy branch clobbered register {register}")
    return vm.reg_read(UC_ARM64_REG_PC)


def exercise(stock_loads, request, *, simulate_preview=True):
    vm = create_vm(stock_loads)

    def write_pointer(location, value):
        vm.mem_write(location, struct.pack("<Q", value))

    write_pointer(REQUEST + 0x10, MODE if request.has_mode else 0)
    vm.mem_write(MODE, struct.pack("<iiQiif", request.hwc_config, request.sf_mode,
                                   request.display, request.width, request.height, request.hz))
    write_pointer(SF + 0x2280, HW)
    write_pointer(HW, VTABLE)
    write_pointer(VTABLE + 0x20, STUB)
    write_pointer(VTABLE + 0x160, STUB + 4)
    if request.vendor_reject:
        write_pointer(SF + 0x28C0, HW)
        write_pointer(VTABLE + 0x278, STUB + 8)

    events = []

    def finish_call(result):
        vm.reg_write(UC_ARM64_REG_X0, result)
        vm.reg_write(UC_ARM64_REG_PC, vm.reg_read(UC_ARM64_REG_LR))

    def on_instruction(_vm, address, _size, _data):
        if address == 0x3BC6E8:  # Physical ID formatting helper
            finish_call(vm.reg_read(UC_ARM64_REG_X0))
        elif address in (0xCD0D20, 0xCD0480):  # Trace and logger stubs
            if address == 0xCD0480:
                caller = vm.reg_read(UC_ARM64_REG_LR)
                events.append({0x4FEE44: "stock external refusal",
                               0x4FEE68: "stock null refusal"}[caller])
            finish_call(0)
        elif address == STUB:
            finish_call(HW)
        elif address == STUB + 4:
            result = request.connection
            if simulate_preview:
                return_address = vm.reg_read(UC_ARM64_REG_LR)
                display_id = vm.reg_read(UC_ARM64_REG_X1)
                result = connection_for_preview(vm, request, return_address, display_id)
            finish_call(result)
        elif address == STUB + 8:
            events.append("stock vendor guard")
            finish_call(1)
        elif address in (SUBMIT, 0x4FF308, 0x4FF340):
            if address == SUBMIT:
                args = tuple(vm.reg_read(reg) for reg in (
                    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
                ))
                if args != (SF + 0x1268, request.display, REQUEST):
                    raise AssertionError(f"unexpected mode-controller arguments {args}")
                events.append("mode controller")
            vm.emu_stop()

    vm.hook_add(UC_HOOK_CODE, on_instruction)
    vm.reg_write(UC_ARM64_REG_SP, STACK)
    vm.reg_write(UC_ARM64_REG_X0, SF)
    vm.reg_write(UC_ARM64_REG_X1, REQUEST)
    vm.emu_start(FUNCTION, 0, count=500)
    return events


def load_segments(raw):
    elf = ELFFile(BytesIO(raw))
    for segment in elf.iter_segments(type="PT_LOAD"):
        if not segment["p_filesz"] or segment["p_flags"] & 2:
            continue
        start = segment["p_vaddr"]
        base = start & ~0xFFF
        data = b"\0" * (start - base) + segment.data()
        yield base, (len(data) + 0xFFF) & ~0xFFF, data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock_elf", type=Path)
    args = parser.parse_args()
    raw = args.stock_elf.read_bytes()
    if sha256(raw).hexdigest() != STOCK_SHA256:
        parser.error("unrecognized stock ELF for xuanyuan OS4.0.3.0.XOACNXM")
    stock_loads = tuple(load_segments(raw))

    target = Request()
    refuse, submit = "stock external refusal", "mode controller"
    cases = (
        ("stock 2K100", replace(target, armed=None), refuse),
        ("preview 2K100", target, submit),
        ("preview 2K60 restore", replace(target, armed=0, sf_mode=0, hwc_config=0, hz=60), submit),
        ("internal stock route", replace(target, display=INTERNAL_DISPLAY, connection=0), submit),
        ("other external display", replace(target, display=EXTERNAL_DISPLAY + 1), refuse),
        ("wrong resolution", replace(target, width=1920, height=1080), refuse),
        ("wrong HWC config", replace(target, hwc_config=2), refuse),
        ("wrong SurfaceFlinger mode", replace(target, sf_mode=2), refuse),
        ("wrong refresh rate", replace(target, hz=120), refuse),
        ("non-finite refresh rate", replace(target, hz=float("nan")), refuse),
        ("arm mismatch", replace(target, armed=0), refuse),
        ("expired preview", replace(target, lifetime_ms=0), refuse),
        ("other thread", replace(target, main_thread=False), refuse),
        ("vendor veto still applies", replace(target, vendor_reject=True), "stock vendor guard"),
        ("null mode uses stock path", replace(target, has_mode=False), "stock null refusal"),
    )
    for name, request, expected in cases:
        actual = exercise(stock_loads, request)
        if actual != [expected]:
            raise AssertionError(f"{name}: expected {[expected]}, observed {actual}")
        print(f"PASS {name}: {expected}")
    print(f"PASS {len(cases)} cases on stock ARM64 instructions (host only)")


if __name__ == "__main__":
    main()
