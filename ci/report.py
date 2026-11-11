#!/usr/bin/env python3
"""Prints the result of the emulator tests from Gradle's XML reports: one line per test, and for
every failure the full message (which includes what was on the screen at that moment).

    ci/report.py app/build/outputs/androidTest-results
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

root_dir = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/androidTest-results"
files = sorted(glob.glob(os.path.join(root_dir, "**", "*.xml"), recursive=True))
total = failed = skipped = 0
failures = []
for path in files:
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in suite.iter("testcase"):
        total += 1
        name = "%s.%s" % (case.get("classname", "").split(".")[-1], case.get("name"))
        bad = case.find("failure") if case.find("failure") is not None else case.find("error")
        if bad is not None:
            failed += 1
            failures.append((name, (bad.get("message") or "") + "\n" + (bad.text or "")))
            status = "FAILED "
        elif case.find("skipped") is not None:
            skipped += 1
            status = "skipped"
        else:
            status = "ok     "
        print("%s %6.1fs  %s" % (status, float(case.get("time") or 0), name))

print("\n%d emulator tests, %d failed, %d skipped (from %d report files in %s)" % (total, failed, skipped, len(files), root_dir))
for name, text in failures:
    print("\n" + "=" * 100 + "\nFAILED: " + name + "\n" + "=" * 100)
    lines = text.strip().splitlines()
    print("\n".join(lines[:120]))
    if len(lines) > 120:
        print("... (%d more lines)" % (len(lines) - 120))
if total == 0:
    print("No test results were found: the tests did not run.")
