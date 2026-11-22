"""Check ABI and execute the compiled eligibility callback with stock mode code."""
import hashlib
import io
import json
from pathlib import Path
import struct
import sys

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
import unicorn.arm64_const as arm

from verify_observer_abi import btf_structs, cfi, versions

root = Path(sys.argv[1])
out = root / 'outputs/dp-direct-2k-20261117'
raw = (out / 'module-build/extbridge_618_mode_cap.ko').read_bytes()
module = ELFFile(io.BytesIO(raw))
kernel = ELFFile(io.BytesIO((root / 'references/gki-android17-6.18-15549627/vmlinux').read_bytes()))
stock_raw = (root / 'outputs/xring-drm-current.ko').read_bytes()
assert hashlib.sha256(stock_raw).hexdigest() == '7a0014feaf4868edcfef31dec9c6f68bce21ccb616179ed5a2812c204b2b1913'
stock = ELFFile(io.BytesIO(stock_raw))
stock_text = stock.get_section_by_name('.text').data()
assert struct.unpack_from('<I', stock_text, 0x2abe8)[0] == 0x6b0a013f
a = btf_structs(kernel.get_section_by_name('.BTF').data())
b = btf_structs((root / 'outputs/external-2k-hbr3-20261117/running-kernel.btf').read_bytes())
sizes = {}
for name in ('module', 'kprobe', 'pt_regs', 'work_struct', 'delayed_work'):
    assert a[name] == b[name], name
    sizes[name] = a[name]['size']
expected = {r[1]: int(r[0], 16) for r in (line.split() for line in
    (root / 'references/gki-android17-6.18-15549627/kernel_aarch64_Module.symvers').read_text().splitlines())}
imported = versions(module)
assert all(expected[k] == v for k, v in imported.items())
assert cfi(module, 'allow_165') == cfi(kernel, 'aggr_pre_handler')
assert cfi(module, 'expire') == 0xa607748c
assert cfi(module, 'init_module') == cfi(stock, 'init_module')
assert cfi(module, 'cleanup_module') == cfi(stock, 'cleanup_module')
assert module.get_section_by_name('.gnu.linkonce.this_module')['sh_size'] == sizes['module']

syms = module.get_section_by_name('.symtab')
callback = syms.get_symbol_by_name('allow_165')[0]
sections = {i: 0x40000000 + i * 0x10000 for i, s in enumerate(module.iter_sections())
            if s.name in ('.text', '.data', '.bss')}
text_base = sections[callback['st_shndx']]
text = bytearray(module.get_section_by_name('.text').data())
for r in module.get_section_by_name('.rela.text').iter_relocations():
    pos = r['r_offset']
    if not callback['st_value'] <= pos < callback['st_value'] + callback['st_size']:
        continue
    s = syms.get_symbol(r['r_info_sym'])
    target = sections[s['st_shndx']] + s['st_value'] + r['r_addend']
    ins = struct.unpack_from('<I', text, pos)[0]
    if r['r_info_type'] == 275:
        delta = ((target & ~4095) - ((text_base + pos) & ~4095)) >> 12
        ins = (ins & ~0x60ffffe0) | ((delta & 3) << 29) | (((delta >> 2) & 0x7ffff) << 5)
    else:
        scale = {278: 0, 285: 2, 286: 3}[r['r_info_type']]
        ins = (ins & ~0x3ffc00) | (((target & 4095) >> scale) << 10)
    struct.pack_into('<I', text, pos, ins)
cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
for index, address in sections.items():
    sec = module.get_section(index)
    cpu.mem_map(address, (sec['sh_size'] + 4095) & ~4095)
    if sec['sh_type'] != 'SHT_NOBITS':
        cpu.mem_write(address, bytes(text) if sec.name == '.text' else sec.data())
REGS, MODE, STOP = 0x51000000, 0x51001000, 0x70000000
cpu.mem_map(REGS, 8192)
cpu.mem_map(STOP, 4096)
enabled_sym = syms.get_symbol_by_name('enabled')[0]
flag = sections[enabled_sym['st_shndx']] + enabled_sym['st_value']

def run_callback(registers, mode, enabled):
    cpu.mem_write(flag, bytes([enabled]))
    cpu.mem_write(REGS, registers)
    cpu.mem_write(MODE, mode)
    cpu.reg_write(arm.UC_ARM64_REG_X0, 0)
    cpu.reg_write(arm.UC_ARM64_REG_X1, REGS)
    cpu.reg_write(arm.UC_ARM64_REG_X30, STOP)
    cpu.emu_start(text_base + callback['st_value'], STOP, count=200)
    assert cpu.reg_read(arm.UC_ARM64_REG_X0) == 0
    result = bytes(cpu.mem_read(REGS, 320))
    assert result[:80] == registers[:80] and result[88:] == registers[88:]
    assert bytes(cpu.mem_read(MODE, len(mode))) == mode
    return struct.unpack_from('<Q', result, 80)[0]

cases = 0
for enabled in (False, True):
    for clock in (241500, 580000, 594000, 594001, 699499, 699500, 699501, 720200):
        for width, height in ((2560, 1440), (1920, 1080), (2560, 1441), (4097, 1440)):
            for budget in (360000, 540000, 699500, 720000):
                uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
                uc.mem_map(0x10000000, (len(stock_text) + 4095) & ~4095)
                uc.mem_write(0x10000000, stock_text)
                for address in (0x20000000, 0x30000000, 0x60000000, STOP):
                    uc.mem_map(address, 0x10000)
                mode = bytearray(68)
                struct.pack_into('<I', mode, 0, clock)
                struct.pack_into('<H', mode, 4, width)
                struct.pack_into('<H', mode, 14, height)
                connector, stream = 0x20006008, 0x20001000
                uc.mem_write(connector - 8, struct.pack('<Q', stream))
                uc.mem_write(0x20005000, bytes(mode))
                uc.mem_write(stream + 88, struct.pack('<QQ', budget, budget * 3))
                uc.mem_write(stream + 44, struct.pack('<i', -1))
                uc.mem_write(stream + 40, b'\1')
                uc.mem_write(stream + 24, struct.pack('<Q', 0x20004000))
                uc.mem_write(0x60000000 + 1824, struct.pack('<Q', 0x1234567812345678))
                uc.reg_write(arm.UC_ARM64_REG_PSTATE, 0x3c5)
                uc.reg_write(arm.UC_ARM64_REG_SP_EL0, 0x60000000)
                uc.reg_write(arm.UC_ARM64_REG_SP, 0x3000f000)
                uc.reg_write(arm.UC_ARM64_REG_X0, connector)
                uc.reg_write(arm.UC_ARM64_REG_X1, 0x20005000)
                uc.reg_write(arm.UC_ARM64_REG_X30, STOP)
                def hook(u, pc, size, data):
                    registers = bytearray(b'\xa5' * 320)
                    struct.pack_into('<Q', registers, 8, MODE)
                    struct.pack_into('<Q', registers, 72, u.reg_read(arm.UC_ARM64_REG_X9))
                    struct.pack_into('<Q', registers, 80, u.reg_read(arm.UC_ARM64_REG_X10))
                    u.reg_write(arm.UC_ARM64_REG_X10, run_callback(bytes(registers), bytes(mode), enabled))
                uc.hook_add(UC_HOOK_CODE, hook, begin=0x1002abe8, end=0x1002abe8)
                before = bytes(uc.mem_read(0x20000000, 0x10000))
                uc.emu_start(0x1002a9f0, STOP, count=1000)
                allowed = width <= 4096 and clock < budget and (clock <= 594000 or
                    enabled and clock == 699500 and (width, height) == (2560, 1440))
                assert (uc.reg_read(arm.UC_ARM64_REG_W0) == 0) == allowed
                assert uc.reg_read(arm.UC_ARM64_REG_SP) == 0x3000f000
                assert bytes(uc.mem_read(0x20000000, 0x10000)) == before
                cases += 1
result = {'module_sha256': hashlib.sha256(raw).hexdigest(), 'btf_layouts_match': sizes,
          'import_crcs_match': len(imported), 'callback_kcfi': hex(cfi(module, 'allow_165')),
          'compiled_callback_and_stock_mode_cases': cases, 'register_memory_canaries': True,
          'scope': 'Offline proof. Physical 2K165 switching and stability remain separate.'}
(out / 'mode-cap-verification.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
