#!/usr/bin/env python3
"""Exercise the bytecode report on synthetic DEX and multidex-container input."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

p = argparse.ArgumentParser()
p.add_argument("--sdk", required=True)
p.add_argument("--jdk", required=True)
p.add_argument("--build-out", required=True)
a = p.parse_args()
root = Path(__file__).resolve().parent
out = Path(a.build_out).resolve()
jdk = Path(a.jdk).resolve()
sdk = Path(a.sdk).resolve()
test = out / "verification"
classes = test / "fixture-classes"
dex = test / "dex"
first_dex = test / "first-dex"
harness = test / "harness"
for path in [classes, dex, first_dex, harness]: path.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env["JAVA_HOME"] = str(jdk)
env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")
def run(*cmd): subprocess.run([str(x) for x in cmd], env=env, check=True)
run(jdk / "bin/javac", "--release", "17", "-d", classes, *sorted((root / "test/fixtures").rglob("*.java")))
run(sdk / "build-tools/36.0.0/d8", "--release", "--min-api", "33", "--lib", sdk / "platforms/android-36/android.jar", "--output", dex, *sorted(classes.rglob("*.class")))
run(sdk / "build-tools/36.0.0/d8", "--release", "--min-api", "33", "--lib", sdk / "platforms/android-36/android.jar", "--output", first_dex, classes / "Unrelated.class")
fixture_jar = test / "fixture.jar"
with zipfile.ZipFile(fixture_jar, "w") as z:
    z.write(first_dex / "classes.dex", "classes.dex")
    z.write(dex / "classes.dex", "classes2.dex")
    z.writestr("README.txt", "Synthetic fixture; not Xiaomi firmware.")
corrupt = test / "corrupt.dex"
corrupt.write_bytes(b"not a dex file")
cp = os.pathsep.join(str(x) for x in [out / "build/classes", *sorted((out / "dependencies").glob("*.jar"))])
run(jdk / "bin/javac", "--release", "17", "-classpath", cp, "-d", harness, root / "test/TargetedDexReportTest.java")
run(jdk / "bin/java", "-classpath", str(harness) + os.pathsep + cp, "app.noti.penprobe.TargetedDexReportTest", dex / "classes.dex", fixture_jar, corrupt)
