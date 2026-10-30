"""Validate installable Core artifacts for the selected Minecraft targets."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def verify(mc, spec, version):
    name = f"irl-core-{version}-{mc}.jar"
    jar = ROOT / "build/libs" / name
    built = ROOT / "build/targets" / mc / "libs" / name
    assert jar.read_bytes() == built.read_bytes(), f"Stale collected artifact: {jar}"
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate archive entries"
        meta = json.loads(archive.read("fabric.mod.json"))
        assert meta["id"] == "irl-core"
        assert meta["version"] == f"{version}-{mc}"
        assert meta["depends"]["minecraft"] == "=" + mc
        assert meta["depends"]["java"] == ">=" + str(spec["java"])
        manifest = archive.read("META-INF/MANIFEST.MF").decode()
        assert "Fabric-Minecraft-Version: " + mc in manifest
        namespace = spec.get("namespace", "intermediary")
        assert "Fabric-Mapping-Namespace: " + namespace in manifest
        assert not any(n.startswith(("net/minecraft/", "net/irisshaders/", "META-INF/jars/", "org/qualet/irl/smoke/")) for n in names)
        classes = [n for n in names if n.endswith(".class")]
        for item in classes:
            major = struct.unpack(">H", archive.read(item)[6:8])[0]
            assert major == spec["java"] + 44, f"Wrong bytecode {major}: {item}"
        prefix = "org/qualet/irl/"
        for item in ["light/FramePipeline", "light/LightRegistry", "light/shadow/ShadowRenderer",
                     "light/shadow/ShadowBaker", "patcher/Patcher", "platform/NativeGl"]:
            assert prefix + item + ".class" in names, item
        assert (prefix + "light/shadow/RawOccluderBatch.class" in names) == (spec["backend"] == "modern")
        assert (prefix + "light/shadow/ImmediateOccluderBatch.class" in names) == (spec["backend"] == "legacy")
    return {"minecraft": mc, "java": spec["java"], "namespace": namespace,
            "classes": len(classes), "jar": str(jar.relative_to(ROOT)),
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mc", action="append")
    args = parser.parse_args()
    specs = json.loads((ROOT / "gradle/minecraft-targets.json").read_text())
    properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line)
    results = [verify(mc, specs[mc], properties["irl_core_version"]) for mc in (args.mc or specs)]
    out = ROOT / "build/validation/artifacts.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(results, indent=4) + "\n", encoding="utf-8")
    print(f"PASS: {len(results)} Core artifacts; metadata, namespace, bytecode and collected hashes match")


if __name__ == "__main__":
    main()
