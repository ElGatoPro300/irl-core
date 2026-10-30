"""Run isolated Core client GPU checks with real Minecraft/Iris/Sodium runtimes."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MARKER = "[Core smoke] NATIVE CAMERA, IRIS, IMAGE RESIZE AND DEPTH DRAW PASSED"


def prepare(mc, iris):
    output = ROOT / "build/validation" / mc
    source = output / "sources/org/qualet/irl/smoke/CoreClientSmoke.java"
    source.parent.mkdir(parents=True, exist_ok=True)
    text = (ROOT / "tools/smoke/CoreClientSmoke.java").read_text(encoding="utf-8")
    if not iris:
        text = text.replace("!IrisShadersState.shadersDisabled()", "false")
        text = text.replace("NATIVE CAMERA, IRIS, IMAGE RESIZE AND DEPTH DRAW PASSED", "NATIVE CAMERA, IMAGE RESIZE AND DEPTH DRAW PASSED (IRIS DISABLED)")
    # Explicit native fixture bindings; production sources use the symbol bridge.
    if mc in ("26.2", "26.3"):
        text = text.replace("client.getOverlay()", "client.gui.overlay()").replace("client.currentScreen", "client.gui.screen()")
    elif mc == "26.1":
        text = text.replace("client.currentScreen", "client.screen")
    if mc.startswith("26."):
        text = text.replace("net.minecraft.util.math.BlockPos", "net.minecraft.core.BlockPos")
        text = text.replace("net.minecraft.util.shape.VoxelShapes", "net.minecraft.world.phys.shapes.Shapes")
        text = text.replace("VoxelShapes.fullCube()", "Shapes.block()")
        text = text.replace("client.scheduleStop()", "client.stop()")
    source.write_text(text, encoding="utf-8")
    resources = output / "resources"
    resources.mkdir(parents=True, exist_ok=True)
    (resources / "fabric.mod.json").write_text(json.dumps({"schemaVersion": 1, "id": "irl_core_smoke",
        "version": "1.0.0", "environment": "client", "entrypoints": {"client": ["org.qualet.irl.smoke.CoreClientSmoke"]},
        "depends": {"irl-core": "*"}}), encoding="utf-8")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__ + " By default 1.20.x uses vanilla; newer targets load Iris/Sodium.")
    parser.add_argument("--mc", action="append")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--without-iris", action="store_true", help="Run vanilla GPU checks without loading Iris/Sodium")
    mode.add_argument("--with-iris", action="store_true", help="Force Iris/Sodium, including legacy targets with the known development selector failure")
    args = parser.parse_args()
    specs = json.loads((ROOT / "gradle/minecraft-targets.json").read_text())
    versions = args.mc or list(specs)
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    results = []
    for mc in versions:
        assert mc in specs, mc
        iris = not args.without_iris and (args.with_iris or not mc.startswith("1.20."))
        output = prepare(mc, iris)
        log_path = output / "client.log"
        print("Checking native client", mc, "with Iris" if iris else "without Iris", flush=True)
        command = [str(wrapper), "runClient", "-Pmc=" + mc, "-PcoreSmoke", "-PcoreSmokeIris=" + str(iris).lower(), "--console=plain"]
        with log_path.open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            try:
                status = process.wait(timeout=300)
            except subprocess.TimeoutExpired:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True)
                else:
                    process.terminate()
                raise RuntimeError("Client timed out: " + str(log_path))
        text = log_path.read_text(encoding="utf-8", errors="replace")
        fatal = re.search(r"Could not execute entrypoint|Mixin apply for mod .* failed|Core native GPU smoke failed", text)
        marker = "[Core smoke] NATIVE CAMERA, IMAGE RESIZE AND DEPTH DRAW PASSED (IRIS DISABLED)" if not iris else MARKER
        passed = status == 0 and not fatal and marker in text and "BUILD SUCCESSFUL" in text[text.rfind(marker):]
        results.append({"minecraft": mc, "passed": passed, "iris": iris, "exit": status, "log": str(log_path.relative_to(ROOT))})
        (ROOT / "build/validation/clients.json").write_text(json.dumps(results, indent=4) + "\n", encoding="utf-8")
        if not passed:
            print(text[-6000:])
            raise RuntimeError("Native client check failed: " + str(log_path))
        print("PASS", mc, flush=True)
    print("Passed", len(results), "native menu/GPU checks. Legacy Iris development launches and world shaderpack rendering require separate validation.")


if __name__ == "__main__":
    main()
