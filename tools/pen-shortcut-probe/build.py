#!/usr/bin/env python3
"""Build an isolated no-permission pen probe using Android SDK command-line tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--sdk", default=os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME")))
parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME"))
parser.add_argument("--out", required=True)
args = parser.parse_args()
if not args.sdk or not args.jdk:
    parser.error("provide --sdk and --jdk, or set ANDROID_SDK_ROOT and JAVA_HOME")
source = Path(__file__).resolve().parent
sdk = Path(args.sdk).resolve()
jdk = Path(args.jdk).resolve()
out = Path(args.out).resolve()
out.mkdir(parents=True, exist_ok=True)
build = out / "build"
if build.exists():
    shutil.rmtree(build)
classes = build / "classes"
dex = build / "dex"
classes.mkdir(parents=True)
dex.mkdir()
env = os.environ.copy()
env["JAVA_HOME"] = str(jdk)
env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")
tools = sdk / "build-tools" / "36.0.0"
android = sdk / "platforms" / "android-36" / "android.jar"
dependency_dir = out / "dependencies"
dependency_dir.mkdir(exist_ok=True)
libraries = []
for dependency in json.loads((source / "dependencies.json").read_text()):
    path = dependency_dir / dependency["name"]
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != dependency["sha256"]:
        with urllib.request.urlopen(dependency["url"], timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != dependency["sha256"]:
            raise RuntimeError("Dependency checksum mismatch: " + dependency["name"])
        path.write_bytes(data)
    libraries.append(path)

def run(*cmd):
    subprocess.run([str(x) for x in cmd], env=env, check=True)

base = build / "base.apk"
run(tools / "aapt2", "link", "-I", android, "--manifest", source / "AndroidManifest.xml", "-A", source / "assets", "-o", base)
java = sorted((source / "src").rglob("*.java"))
classpath = os.pathsep.join(str(x) for x in [android, *libraries])
run(jdk / "bin" / "javac", "--release", "17", "-classpath", classpath, "-d", classes, *java)
run(tools / "d8", "--release", "--min-api", "33", "--lib", android, "--output", dex, *sorted(classes.rglob("*.class")), *libraries)
unsigned = build / "unsigned.apk"
shutil.copyfile(base, unsigned)
with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
    for item in sorted(dex.glob("*.dex")):
        archive.write(item, item.name)
aligned = build / "aligned.apk"
run(tools / "zipalign", "-f", "4", unsigned, aligned)
key = out / "probe-debug.p12"
if not key.exists():
    run(jdk / "bin" / "keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
        "-storepass", "android", "-keypass", "android", "-alias", "probe", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "3650", "-dname", "CN=Noti Pen Probe (local test)")
apk = out / "noti-pen-probe.apk"
run(tools / "apksigner", "sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android",
    "--ks-key-alias", "probe", "--out", apk, aligned)
run(tools / "apksigner", "verify", "--verbose", apk)
run(tools / "aapt2", "dump", "badging", apk)
permissions = subprocess.check_output([str(tools / "aapt2"), "dump", "permissions", str(apk)], env=env, text=True)
if "uses-permission" in permissions:
    raise RuntimeError("Probe unexpectedly declares a permission: " + permissions)
print("No Android permissions declared.")
print("APK:", apk)
print("SHA256:", hashlib.sha256(apk.read_bytes()).hexdigest())
