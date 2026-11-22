"""Verify the shipped APK and KernelSU ZIP contain exactly the same startup code."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("module", type=Path)
args = parser.parse_args()
source = Path(__file__).resolve().parents[2]
with ZipFile(args.apk) as apk, ZipFile(args.module) as module:
    jar = apk.read("assets/extbridge.jar")
    assert jar == module.read("extbridge.jar"), "daemon code differs"
    for name in ("module.prop", "service.sh", "customize.sh"):
        data = module.read(name)
        assert data == apk.read("assets/autostart/" + name), name + " differs"
        assert data == (source / "helper/assets/autostart" / name).read_bytes(), name + " is stale"
        assert b"\r" not in data, name + " must use LF"
    assert "id=extbridge_autostart\n" in module.read("module.prop").decode()
    print(json.dumps({
        "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "module_sha256": hashlib.sha256(args.module.read_bytes()).hexdigest(),
        "daemon_sha256": hashlib.sha256(jar).hexdigest(),
        "zip_entries": module.namelist(),
        "identical_assets": True,
    }, indent=2))
