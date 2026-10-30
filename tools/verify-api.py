"""Check public/protected binary APIs against a consumer's existing Core JARs."""
import argparse
import io
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def exported(data):
    stream = io.BytesIO(data)
    def number(size):
        return int.from_bytes(stream.read(size), "big")
    assert number(4) == 0xCAFEBABE
    stream.read(4)
    pool = [None] * number(2)
    index = 1
    while index < len(pool):
        tag = number(1)
        if tag == 1:
            pool[index] = stream.read(number(2)).decode("utf-8", errors="replace")
        elif tag in (3, 4): stream.read(4)
        elif tag in (5, 6): stream.read(8); index += 1
        elif tag in (7, 8, 16, 19, 20): pool[index] = number(2)
        elif tag in (9, 10, 11, 12, 17, 18): stream.read(4)
        elif tag == 15: stream.read(3)
        else: raise ValueError(f"Unknown constant tag {tag}")
        index += 1
    access = number(2)
    owner = pool[pool[number(2)]]
    parent_index = number(2)
    parent = pool[pool[parent_index]] if parent_index else None
    interfaces = tuple(pool[pool[number(2)]] for _ in range(number(2)))
    api = set()
    if access & 1:
        api.add(("class", owner, parent, interfaces))
    for kind in ("field", "method"):
        for _ in range(number(2)):
            flags, name, desc = number(2), pool[number(2)], pool[number(2)]
            if access & 1 and flags & 5:
                api.add((kind, owner, name, desc, flags & 0x040D))
            for _ in range(number(2)):
                stream.read(2)
                stream.read(number(4))
    return api


def catalog(jar):
    api = set()
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith("org/qualet/irl/") and name.endswith(".class"):
                api.update(exported(archive.read(name)))
    return api


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-root", type=Path, required=True, help="Existing lib/<mc>/ Core JAR folders")
    args = parser.parse_args()
    specs = json.loads((ROOT / "gradle/minecraft-targets.json").read_text())
    version = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line)["irl_core_version"]
    for mc in specs:
        previous = list((args.reference_root / mc).glob("irl-core-*.jar"))
        assert len(previous) == 1, f"Expected one reference Core for {mc}"
        current = ROOT / f"build/libs/irl-core-{version}-{mc}.jar"
        old, new = catalog(previous[0]), catalog(current)
        missing = old - new
        assert not missing, f"{mc}: removed binary API: {sorted(missing, key=str)}"
        print(f"PASS {mc}: preserved {len(old)} public/protected class and member contracts")


if __name__ == "__main__":
    main()
