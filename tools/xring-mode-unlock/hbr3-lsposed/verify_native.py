"""Build the offline trial library and exercise its actual AArch64 selection path."""
import argparse
import hashlib
import itertools
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from patch_xdm import ORIGINAL_SHA256, PATCH_OFFSET
from verify_patch import exercise


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("stock", "selector", "capture", "output"):
        p.add_argument(name, type=Path)
    args = p.parse_args()
    stock, selector = args.stock.read_bytes(), args.selector.read_bytes()
    if hashlib.sha256(stock).hexdigest() != ORIGINAL_SHA256:
        p.error("unsupported stock library")
    if hashlib.sha256(selector).hexdigest() != "d92809f8fb5e9dd3a39ddaaf87425eb4d2b22f4f55dbba88d0d228947596d684":
        p.error("unexpected selector assembly")
    result = stock[:PATCH_OFFSET] + selector + stock[PATCH_OFFSET + len(selector):]
    capture = re.findall(r"^  (\d+)x(\d+)@([\d.]+) clock_kHz=(\d+)", args.capture.read_text(), re.M)
    assert ("2560", "1440", "119.99759", "497750") in capture
    real = [(i + 7, int(w), int(h), float(hz)) for i, (w, h, hz, _) in enumerate(capture)]
    distractors = [(17, 1920, 1080, 120.0), (29, 2560, 1440, 119.88)]
    cases = list(itertools.permutations(real + distractors))
    cases += list(itertools.permutations([m for m in real if m[3] < 119] + distractors))
    cases += [[(91, 2560, 1440, 59.95055), (0, 2560, 1440, hz)]
              for hz in (119.97, 119.98, 120.0, 120.02, 120.03)]
    cases += [[(8, 2560, 1440, 60), (0, 2560, 1440, 119.99759), (33, 2560, 1440, 120)]]
    for case in cases:
        stock_key = exercise(stock, case, original=True)
        expected = next((key for key, w, h, hz in case
                         if (w, h) == (2560, 1440) and 119.98 <= hz <= 120.02), stock_key)
        assert exercise(result, case) == expected, case
    if args.output.exists():
        if args.output.read_bytes() != result:
            p.error("different output file already exists")
    else:
        with args.output.open("xb") as f:
            f.write(result)
    print(json.dumps({"instruction_cases": len(cases), "stock_sha256": ORIGINAL_SHA256,
                      "candidate_sha256": hashlib.sha256(result).hexdigest(),
                      "hardware_2k120_verified": False}, indent=2))


if __name__ == "__main__":
    main()
