"""Local source/XML subset; optional finished-APK evidence checks. Never a control-flow proof.
No network, device, build, install or download actions. Java tests are not simulated here.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile
import xml.etree.ElementTree as ET


def verify(root, apk=None, dexdump=None, build_log=None):
    checks = []

    def check(value, label):
        if not value:
            raise AssertionError(label)
        checks.append(label)

    ns = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(root / "AndroidManifest.xml").getroot()
    check(manifest.get("package") == "org.offline.screenmemory", "distinct package")
    check([e.get(ns + "name") for e in manifest.findall("uses-permission")] ==
          ["android.permission.FOREGROUND_SERVICE"], "only own foreground-service permission")
    sdk = manifest.find("uses-sdk")
    check(sdk is not None and sdk.get(ns + "minSdkVersion") == "31" and
          sdk.get(ns + "targetSdkVersion") == "31", "API31")
    app = manifest.find("application")
    check(app is not None and app.get(ns + "label") == "Screen Memory" and
          app.get(ns + "allowBackup") == "false", "label and backup policy")
    check([e.tag for e in app] == ["activity", "service"], "only Activity and own service")
    check(app.find("service").get(ns + "exported") == "false", "own service non-exported")
    check(app.find("activity").get(ns + "name") == "org.offline.screenmemory.MainActivity", "launcher")
    theme = ET.parse(root / "res/values/styles.xml").getroot()
    items = {e.get("name"): e.text for e in theme.findall("style/item")}
    check(items.get("android:windowIsTranslucent") == "true" and
          items.get("android:backgroundDimEnabled") == "false" and
          items.get("android:windowBackground") == "@android:color/transparent", "transparent undimmed window")
    source = "\n".join(f.read_text() for f in (root / "src").rglob("*.java"))
    check(len(re.findall(r"\bbindService\(", source)) == 1 and
          "bindService(intent, owned, 0)" in source, "sole vendor bind flags zero")
    check(not re.search(r"BIND_AUTO_CREATE|\bstartService\(|\bstopService\(|transact\(|getInterfaceDescriptor\(|java\.net|java\.lang\.reflect|System\.load", source), "forbidden source operations absent")
    service = (root / "src/org/offline/screenmemory/ControlService.java").read_text()
    check(service.count("current.send(message)") == 1 and
          "Message.obtain(null, what, 0, 0)" in service and
          'putBoolean("key_type", false)' in service and
          'putBoolean("is_first", false)' in service and "message.replyTo = reply" in service, "single payload construction site with own reply")
    check("START_NOT_STICKY" in service and "FLAG_IMMUTABLE" in service and
          "startForeground(1, notification())" in service, "foreground/replay source subset")
    check("new BindingSession(" in service, "shared registration controller")
    check("OneShotStep" not in source, "obsolete controller removed")
    build = (root / "build.ps1").read_text()
    check("--release','11','-Xlint:all','-Werror" in build and "screen-memory-debug.apk" in build and
          "'compile','--dir','res'" in build, "manual build flags/resources/output")
    check(manifest.get(ns + "versionCode") == "3" and manifest.get(ns + "versionName") == "1.2", "data preserving update version")
    check(app.get(ns + "icon") == "@drawable/ic_screen_memory" and
          app.get(ns + "banner") == "@drawable/tv_banner", "local discovery resources")
    for resource in ("ic_screen_memory", "tv_banner"):
        ET.parse(root / ("res/drawable/" + resource + ".xml"))
    check('PendingIntent.getActivity(this, 1,' in service and '.setContentIntent(open)' in service
          and '"Open", open' in service and 'PendingIntent.getService(this, 0,' in service,
          "distinct explicit Open and private Stop pending intents source subset")
    check("'UiPolicy'" in build and "'--java',($b+'/generated')" in build, "presentation tests and generated resources built")
    activity = (root / "src/org/offline/screenmemory/MainActivity.java").read_text()
    config = activity[activity.index("private void config()"):activity.index("private void labeled(")]
    check("ScrollView" not in config and "UiPolicy.panel(" in config, "fixed metric configuration source subset")
    check("lp.gravity = android.view.Gravity.CENTER" in config, "whole-canvas panel centering source subset")
    check("operation.reply(message.what, message.arg1)" in service and "connection == this" in service,
          "reply pair forwarded under connection identity source subset")
    check("Catalog.initialize(repository.load(), repository)" in
          (root / "src/org/offline/screenmemory/MemoryApp.java").read_text(), "load before initialization source subset")
    check("boolean establishingNative = app.runtime.reference && Memory.NATIVE_ID.equals(target.id)" in activity
          and "dismiss && !establishingNative" in activity, "reference-only Native selection stays open source subset")
    check("previewAllowed(z)" in activity and "false, true)" in activity,
          "explicit preview purpose and UI latch guard source subset")
    result = {"status": "SOURCE SUBSET ONLY; Java/Android execution and independent review are separate gates", "checks": checks}
    if apk is not None:
        check(dexdump is not None and build_log is not None, "binary evidence supplied together")
        with zipfile.ZipFile(apk) as archive:
            check(archive.testzip() is None, "APK CRC valid")
            check([n for n in archive.namelist() if n.endswith(".dex")] == ["classes.dex"], "single DEX")
            check(not any(n.startswith(("lib/", "assets/")) for n in archive.namelist()), "no native/executable assets")
            dex = archive.read("classes.dex")
        check(dex[:8] == b"dex\n039\0", "DEX039")

        def u32(offset):
            return struct.unpack_from("<I", dex, offset)[0]

        strings = []
        for i in range(u32(56)):
            offset = u32(u32(60) + i * 4)
            while dex[offset] & 128:
                offset += 1
            offset += 1
            strings.append(dex[offset:dex.index(0, offset)].decode("utf-8", "replace"))
        types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
        methods = []
        for i in range(u32(88)):
            cls, _, name = struct.unpack_from("<HHI", dex, u32(92) + i * 8)
            methods.append((types[cls], strings[name]))
        check(not any(c in {"Landroid/os/Binder;", "Landroid/os/IBinder;"} or
                      c.startswith(("Ljava/net/", "Ljava/lang/reflect/")) for c, _ in methods), "no direct Binder/network/reflection method refs")
        check(not any(n in {"transact", "getInterfaceDescriptor", "startService", "stopService", "loadLibrary", "forName"} for _, n in methods), "no forbidden DEX refs")
        check({n for c, n in methods if c == "Landroid/os/Messenger;"} == {"<init>", "send"}, "Messenger method allowlist")
        check({"Lorg/offline/screenmemory/Catalog$InitializationStore;",
               "Lorg/offline/screenmemory/Operation;", "Lorg/offline/screenmemory/UiPolicy;",
               "Lorg/offline/screenmemory/SelectGesture;"}.issubset(set(types)),
              "production initialization/controller/input classes present")
        check("Digital zoom limit reached; Native reference retained (estimated)" in strings,
              "estimated boundary status in DEX")
        check("com.vt.tv.laserprojectorservice.LaserProjectorService" in strings, "exact vendor component")
        dump = read_evidence(dexdump)
        check(len(re.findall(r"invoke-virtual[^\n]*Landroid/os/Messenger;\.send:", dump)) == 1, "single DEX send site; dataflow needs review")
        log = read_evidence(build_log)
        check("org.offline.screenmemory" in log and "android.permission.FOREGROUND_SERVICE" in log, "binary manifest evidence present; manual decode required")
        check("Verified using v3 scheme (APK Signature Scheme v3): true" in log, "official signature evidence")
        check(re.search(r"(?m)^Verification (?:successful|succesful)\s*$", log) is not None, "zipalign evidence")
        check("PASS 21 assertions" in log and "Tests passed: 18" in log and
              "Screen Memory tests passed:" in log, "three real host summaries present")
        # Test labels and disassembled application strings can contain "error";
        # recognize compiler/tool diagnostics rather than searching their vocabulary.
        check(not re.search(r"(?im)^(?:[^\n]*\.java:\d+:\s*(?:warning|error):|"
                            r"(?:warning|error)(?::|\s+in\b)|[^\n]+:\d+:\s*(?:warning|error):)", log), "clean build diagnostics")
        result.update(status="AUTOMATED SUBSET ONLY: independent binary manifest/payload/control-flow review required",
                      apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
    return result


def read_evidence(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="?", type=Path)
    parser.add_argument("--dexdump", type=Path)
    parser.add_argument("--build-log", type=Path)
    args = parser.parse_args()
    if args.apk is None and (args.dexdump or args.build_log):
        parser.error("supply APK with both binary evidence files")
    print(json.dumps(verify(Path(__file__).resolve().parent, args.apk, args.dexdump, args.build_log), indent=2))


if __name__ == "__main__":
    main()
