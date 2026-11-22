"""Compare real kernel BTF, KCFI entry hashes and imported symbol versions."""

import argparse
import io
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile


def btf_structs(data):
    magic, version, flags, header, toff, tlen, soff, slen = struct.unpack_from("<HBB5I", data)
    assert magic == 0xEB9F and version == 1
    strings = data[header + soff:header + soff + slen]

    def name(offset):
        return strings[offset:strings.index(0, offset)].decode()

    pos, end = header + toff, header + toff + tlen
    result = {}
    while pos < end:
        noff, info, size = struct.unpack_from("<3I", data, pos)
        pos += 12
        kind, count = (info >> 24) & 31, info & 0xFFFF
        extra = {1: 4, 2: 0, 3: 12, 4: 12 * count, 5: 12 * count,
                 6: 8 * count, 7: 0, 8: 0, 9: 0, 10: 0, 11: 0,
                 12: 0, 13: 8 * count, 14: 4, 15: 12 * count,
                 16: 0, 17: 4, 18: 0, 19: 12 * count}[kind]
        if kind == 4:
            fields = []
            for i in range(count):
                n, tid, bits = struct.unpack_from("<3I", data, pos + 12 * i)
                fields.append((name(n), bits))
            result[name(noff)] = {"size": size, "members": fields}
        pos += extra
    assert pos == end
    return result


def versions(elf):
    data = elf.get_section_by_name("__versions").data()
    return {data[i + 8:i + 64].split(b"\0")[0].decode(): struct.unpack_from("<Q", data, i)[0]
            for i in range(0, len(data), 64)}


def cfi(elf, function):
    if not hasattr(elf, "extbridge_symtab"):
        elf.extbridge_symtab = elf.get_section_by_name(".symtab")
    symbol = elf.extbridge_symtab.get_symbol_by_name(function)[0]
    section = elf.get_section(symbol["st_shndx"])
    offset = symbol["st_value"] - section["sh_addr"]
    return struct.unpack_from("<I", section.data(), offset - 4)[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("vmlinux", type=Path)
    parser.add_argument("running_btf", type=Path)
    parser.add_argument("observer", type=Path)
    parser.add_argument("current_pd", type=Path)
    parser.add_argument("symvers", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    kernel = ELFFile(io.BytesIO(args.vmlinux.read_bytes()))
    observer = ELFFile(io.BytesIO(args.observer.read_bytes()))
    current = ELFFile(io.BytesIO(args.current_pd.read_bytes()))
    a = btf_structs(kernel.get_section_by_name(".BTF").data())
    b = btf_structs(args.running_btf.read_bytes())
    sizes = {}
    for name in ("module", "kprobe", "pt_regs"):
        assert a[name] == b[name], "BTF layout mismatch: " + name
        sizes[name] = a[name]["size"]
    # Stack guard load emitted by both compilers reads task_struct+1824.
    for structs in (a, b):
        assert ("stack_canary", 1824 * 8) in structs["task_struct"]["members"]
    expected = {r[1]: int(r[0], 16) for r in
                (line.split() for line in args.symvers.read_text().splitlines())}
    current_versions = versions(current)
    shared = set(expected) & set(current_versions)
    assert all(expected[k] == current_versions[k] for k in shared)
    imported = versions(observer)
    assert all(expected[k] == v for k, v in imported.items())
    assert cfi(kernel, "aggr_pre_handler") == cfi(observer, "observe")
    assert cfi(current, "init_module") == cfi(observer, "init_module")
    assert cfi(current, "cleanup_module") == cfi(observer, "cleanup_module")
    assert observer.get_section_by_name(".gnu.linkonce.this_module")["sh_size"] == sizes["module"]
    report = {"btf_identical_layouts": sizes, "stack_canary_offset": 1824,
              "current_pd_shared_crcs_match": len(shared),
              "observer_import_crcs_match": imported,
              "kprobe_callback_kcfi": hex(cfi(observer, "observe")),
              "init_exit_kcfi_match_current_module": True,
              "compiler": "Ubuntu Clang 21.1.8; official headers/config/build tools from Android Clang 22 build 15549627",
              "scope": "ABI preflight only; module loading remains a separate device check."}
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
