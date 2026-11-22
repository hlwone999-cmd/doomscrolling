#!/usr/bin/env python3
"""Static checks on the Android project that can run without an Android SDK.
Not a compiler: it catches the mistakes a compiler would also catch in
resources and imports, plus project rules (offline, tokens only, no TODOs).

    python replica/tests/check_project.py      # exit 1 on any failure
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
APP = os.path.join(ROOT, "android", "app", "src")
MAIN = os.path.join(APP, "main")
KT = os.path.join(MAIN, "java", "app", "tidemark")
RES = os.path.join(MAIN, "res")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

FAILS = []


def check(name, cond, detail=""):
    print(("  ok    " if cond else "  FAIL  ") + name + ("" if cond else "  -> " + str(detail)[:900]))
    if not cond:
        FAILS.append(name)


def files(base, ext):
    for d, _, fs in os.walk(base):
        for f in fs:
            if f.endswith(ext):
                yield os.path.join(d, f)


def read(p):
    return open(p, encoding="utf-8").read()


def strings(path):
    root = ET.parse(path).getroot()
    return {e.get("name"): (e.text or "") for e in root.findall("string")}


def fmt_args(s):
    return sorted(set(re.findall(r"%(\d+)\$[ds]", s)))


def main():
    kotlin = {p: read(p) for p in files(os.path.join(APP), ".kt")}
    main_kt = {p: t for p, t in kotlin.items() if os.sep + "main" + os.sep in p}
    xmls = {p: read(p) for p in files(MAIN, ".xml")}

    print("Offline and manifest")
    manifest = read(os.path.join(MAIN, "AndroidManifest.xml"))
    perms = re.findall(r'uses-permission\s+android:name="([^"]+)"', manifest)
    check("no INTERNET permission", "android.permission.INTERNET" not in perms, perms)
    check("no ACCESS_NETWORK_STATE either", "android.permission.ACCESS_NETWORK_STATE" not in perms, perms)
    check("allowBackup false", 'android:allowBackup="false"' in manifest)
    for comp in re.findall(r'android:name="\.([\w.]+)"', manifest):
        cls = comp.split(".")[-1]
        sub = comp.split(".")[:-1]
        path = os.path.join(KT, *sub, "")
        found = any(re.search(r"\bclass\s+%s\b" % cls, t) for p, t in main_kt.items() if os.path.dirname(p) + os.sep == path or p.startswith(path))
        check("manifest component exists: " + comp, found)
    gradle = read(os.path.join(ROOT, "android", "app", "build.gradle.kts")) + read(os.path.join(ROOT, "android", "gradle", "libs.versions.toml"))
    banned = ["firebase", "crashlytics", "sentry", "revenuecat", "billing", "okhttp", "retrofit", "ktor", "play-services", "supabase", "admob"]
    check("no network/analytics/billing libraries", not [b for b in banned if b in gradle.lower()], [b for b in banned if b in gradle.lower()])

    print("Detection rules")
    rules = json.loads(read(os.path.join(MAIN, "assets", "detectors", "v1.json")))
    pkgs = [a["package"] for a in rules["apps"]]
    db = read(os.path.join(KT, "data", "AppDatabase.kt"))
    seeds = re.findall(r'listOf\("([\w.]+)", "[^"]+", "[\w_]+", \d\)', db)
    check("every rule app is seeded", set(pkgs) <= set(seeds), set(pkgs) - set(seeds))
    queries = re.findall(r'<package android:name="([\w.]+)"', manifest)
    check("every rule app is in <queries>", set(pkgs) <= set(queries), set(pkgs) - set(queries))
    check("nothing claims verified yet", all(not a.get("verifiedVersions") for a in rules["apps"]),
          [a["package"] for a in rules["apps"] if a.get("verifiedVersions")])

    print("Strings")
    en = strings(os.path.join(RES, "values", "strings.xml"))
    idn = strings(os.path.join(RES, "values-in", "strings.xml"))
    used = set()
    for t in main_kt.values():
        used |= set(re.findall(r"R\.string\.(\w+)", t))
    for t in xmls.values():
        used |= set(re.findall(r"@string/(\w+)", t))
    for p, t in kotlin.items():
        if os.sep + "androidTest" + os.sep in p:
            used |= set(re.findall(r"R\.string\.(\w+)", t))
    missing = sorted(used - set(en))
    check("every used string exists (%d used)" % len(used), not missing, missing)
    untranslated = sorted(k for k in en if k not in idn and k != "app_name")
    check("every string has an Indonesian translation", not untranslated, untranslated)
    extra = sorted(set(idn) - set(en))
    check("no Indonesian-only keys", not extra, extra)
    bad_fmt = [k for k in en if k in idn and fmt_args(en[k]) != fmt_args(idn[k])]
    check("format arguments match EN/ID", not bad_fmt, bad_fmt)
    unused = sorted(set(en) - used - {"app_name", "stub_next"})
    print("  info  unused strings: %d %s" % (len(unused), unused[:12]))

    print("Other resources")
    for kind, folder_glob in [("drawable", "drawable"), ("layout", "layout"), ("xml", "xml")]:
        refs = set()
        for t in main_kt.values():
            refs |= set(re.findall(r"R\.%s\.(\w+)" % kind, t))
        for t in xmls.values():
            refs |= set(re.findall(r"@%s/(\w+)" % kind, t))
        have = {os.path.splitext(f)[0] for d in os.listdir(RES) if d.startswith(folder_glob) for f in os.listdir(os.path.join(RES, d))}
        check("every R.%s exists" % kind, refs <= have, refs - have)
    ids_used = set()
    for t in main_kt.values():
        ids_used |= set(re.findall(r"R\.id\.(\w+)", t))
    ids_have = set()
    for t in xmls.values():
        ids_have |= set(re.findall(r'@\+id/(\w+)', t))
    check("every R.id exists", ids_used <= ids_have, ids_used - ids_have)
    colors = set()
    for t in xmls.values():
        colors |= set(re.findall(r"@color/(\w+)", t))
    for folder in ("values", "values-night"):
        have = set(re.findall(r'<color name="(\w+)"', read(os.path.join(RES, folder, "colors.xml"))))
        check("colors defined in %s" % folder, colors <= have, colors - have)

    print("Brand resources")
    mip = os.path.join(RES, "mipmap-anydpi-v26")
    for name in ("ic_launcher", "ic_launcher_round"):
        f = os.path.join(mip, name + ".xml")
        ok = os.path.exists(f)
        check("adaptive icon %s exists" % name, ok)
        if ok:
            t = read(f)
            layers = re.findall(r'<(background|foreground|monochrome) android:drawable="@drawable/(\w+)"', t)
            check("%s has background, foreground and monochrome" % name,
                  sorted(l for l, _ in layers) == ["background", "foreground", "monochrome"], layers)
            missing_layers = [d for _, d in layers if not os.path.exists(os.path.join(RES, "drawable", d + ".xml"))]
            check("%s layers exist" % name, not missing_layers, missing_layers)
    check("manifest uses the launcher icons",
          'android:icon="@mipmap/ic_launcher"' in manifest and 'android:roundIcon="@mipmap/ic_launcher_round"' in manifest)
    font_dir = os.path.join(RES, "font")
    fonts = set()
    for t in main_kt.values():
        fonts |= set(re.findall(r"R\.font\.(\w+)", t))
    have_fonts = {os.path.splitext(f)[0] for f in os.listdir(font_dir)} if os.path.isdir(font_dir) else set()
    check("every R.font exists (%s)" % ", ".join(sorted(fonts)), fonts <= have_fonts, fonts - have_fonts)
    for f in (os.listdir(font_dir) if os.path.isdir(font_dir) else []):
        with open(os.path.join(font_dir, f), "rb") as fh:
            magic = fh.read(4)
        check("font %s is TrueType/OpenType" % f, magic in (bytes([0, 1, 0, 0]), b"OTTO", b"true"), magic)
    lic = os.path.join(MAIN, "assets", "licenses", "inter_OFL.txt")
    check("Inter licence ships with the app", os.path.exists(lic) and "SIL Open Font License" in read(lic))
    # Every point of the icon foreground must sit inside the safe circle (centre 54,54, radius 33).
    fg = read(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"))
    worst = 0.0
    for d in re.findall(r'android:pathData="([^"]+)"', fg):
        x = y = 0.0
        start = (0.0, 0.0)
        for cmd, args in re.findall(r"([MLHVCZmlhvcz])([^MLHVCZmlhvcz]*)", d):
            nums = [float(v) for v in re.findall(r"-?\d+(?:\.\d+)?", args)]
            pts = []
            if cmd in "MLC":
                pts = [(nums[i], nums[i + 1]) for i in range(0, len(nums), 2)]
            elif cmd == "H":
                pts = [(nums[-1], y)]
            elif cmd == "V":
                pts = [(x, nums[-1])]
            elif cmd == "h":
                pts = [(x + nums[-1], y)]
            elif cmd == "v":
                pts = [(x, y + nums[-1])]
            for px, py in pts:
                worst = max(worst, ((px - 54) ** 2 + (py - 54) ** 2) ** 0.5)
            if cmd == "M":
                start = pts[-1]
            if cmd in "hv":
                x, y = start  # relative ticks: the next segment starts from the spine again (each tick has its own M)
            elif pts:
                x, y = pts[-1]
    check("icon foreground inside the 66 dp safe zone (furthest point %.1f + 2 stroke <= 33)" % worst, worst + 2 <= 33, worst)

    print("Kotlin hygiene")
    for p, t in kotlin.items():
        rel = os.path.relpath(p, APP)
        body = re.sub(r'"""(.*?)"""', '""', t, flags=re.S)
        body = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', body)
        body = re.sub(r"'(?:\\.|[^'\\])'", "''", body)
        body = re.sub(r"//[^\n]*", "", body)
        body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
        for o, c in ["{}", "()", "[]"]:
            if body.count(o) != body.count(c):
                check("balanced %s in %s" % (o + c, rel), False, (body.count(o), body.count(c)))
        pkg = re.search(r"^package\s+([\w.]+)", t, re.M)
        expected = os.path.dirname(os.path.relpath(p, os.path.join(APP, rel.split(os.sep)[0], "java"))).replace(os.sep, ".")
        if pkg and pkg.group(1) != expected:
            check("package matches folder: " + rel, False, (pkg.group(1), expected))
    check("braces, parens and brackets balance in all %d Kotlin files" % len(kotlin),
          not [f for f in FAILS if f.startswith("balanced")])
    todo = [os.path.relpath(p, APP) for p, t in kotlin.items() if re.search(r"\b(TODO|FIXME|XXX)\b", t)]
    check("no TODO / FIXME", not todo, todo)
    hexes = [os.path.relpath(p, APP) for p, t in main_kt.items()
             if not p.endswith("DesignTokens.kt") and re.search(r"Color\(0x[0-9A-Fa-f]{8}\)|#[0-9A-Fa-f]{6}\b", re.sub(r"//[^\n]*", "", t))]
    check("no raw colours outside DesignTokens.kt", not hexes, hexes)

    print("Imports resolve")
    declared = {}
    for p, t in kotlin.items():
        pkg = re.search(r"^package\s+([\w.]+)", t, re.M).group(1)
        names = set(re.findall(r"^(?:\s*(?:private|internal|public|abstract|open|data|sealed|enum|inline|const|@\w+(?:\([^)]*\))?)\s+)*(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]+>\s+)?(?:[\w.]+\.)?(\w+)", t, re.M))
        declared.setdefault(pkg, set()).update(names)
    unresolved = []
    for p, t in kotlin.items():
        for imp in re.findall(r"^import\s+(app\.scrollcount[\w.]*)", t, re.M):
            pkg, name = imp.rsplit(".", 1)
            if name == "R" and pkg == "app.tidemark":
                continue
            if pkg in declared and name in declared[pkg]:
                continue
            # nested: app.tidemark.x.Outer.Inner
            outer_pkg, outer = pkg.rsplit(".", 1) if "." in pkg else (pkg, "")
            if outer_pkg in declared and outer in declared[outer_pkg]:
                continue
            unresolved.append("%s: %s" % (os.path.relpath(p, APP), imp))
    check("every app.tidemark import is declared", not unresolved, unresolved)

    print()
    print("%d failing" % len(FAILS))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
