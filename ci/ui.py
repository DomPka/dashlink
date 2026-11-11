#!/usr/bin/env python3
"""Drives the emulator's screen over plain adb, for ci/smoke.sh.

A view is named by a selector: "id=connect" (the end of its resource id) or "text=REGEX"
(its whole text, case-insensitive).

    ui.py tap SELECTOR [seconds]            wait for the view, tap it; fails if it never shows
    ui.py tap-if SELECTOR [seconds]         tap it if it shows up in time; prints "tapped" or "absent"
    ui.py expect SELECTOR REGEX [seconds]   wait until the view's text contains REGEX
    ui.py absent SELECTOR                   fails if the view is on the screen right now
    ui.py type SELECTOR TEXT                replace the content of a text field ("" just empties it)
    ui.py texts                             print every text on the screen
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=120).stdout


def screen():
    """The window hierarchy, or None when Android will not hand it out at this moment."""
    out = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    start, end = out.find("<?xml"), out.rfind("</hierarchy>")
    if start < 0 or end < 0:
        return None
    try:
        return ET.fromstring(out[start:end + len("</hierarchy>")])
    except ET.ParseError:
        return None


def find(root, selector):
    kind, _, value = selector.partition("=")
    if root is None:
        return None
    for node in root.iter("node"):
        if kind == "id" and node.get("resource-id", "").endswith(":id/" + value):
            return node
        if kind == "text" and re.fullmatch(value, node.get("text", ""), re.I | re.S):
            return node
    return None


def texts(root):
    if root is None:
        return ["(the screen could not be read)"]
    return ["[%s] %s" % (n.get("package", ""), n.get("text")) for n in root.iter("node") if n.get("text")]


def wait(selector, seconds, accept=lambda node: True):
    end = time.time() + seconds
    while True:
        root = screen()
        node = find(root, selector)
        if node is not None and accept(node):
            return node, root
        if time.time() > end:
            return None, root
        time.sleep(1)


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def fail(message, root):
    print("ui.py: " + message, file=sys.stderr)
    print("on the screen:\n  " + "\n  ".join(texts(root)), file=sys.stderr)
    sys.exit(1)


def main():
    args = sys.argv[1:]
    cmd = args[0] if args else ""
    if cmd == "texts":
        print("\n".join(texts(screen())))
    elif cmd in ("tap", "tap-if"):
        seconds = float(args[2]) if len(args) > 2 else 15
        node, root = wait(args[1], seconds)
        if node is None:
            if cmd == "tap":
                fail("nothing to tap for %s after %d s" % (args[1], seconds), root)
            print("absent")
        else:
            tap(node)
            print("tapped")
    elif cmd == "expect":
        seconds = float(args[3]) if len(args) > 3 else 15
        node, root = wait(args[1], seconds, lambda n: re.search(args[2], n.get("text", ""), re.I | re.S))
        if node is None:
            shown = find(root, args[1])
            fail("%s does not say /%s/ after %d s; it says: %r" % (
                args[1], args[2], seconds, shown.get("text") if shown is not None else None), root)
        print(node.get("text"))
    elif cmd == "absent":
        root = screen()
        if find(root, args[1]) is not None:
            fail("%s is on the screen" % args[1], root)
    elif cmd == "type":
        node, root = wait(args[1], 15)
        if node is None:
            fail("no text field %s" % args[1], root)
        tap(node)
        time.sleep(0.5)
        # End of the field, then more backspaces than it can hold.
        adb("shell", "input", "keyevent", "123", *(["67"] * 48))
        if args[2]:
            adb("shell", "input", "text", args[2])
        # Not read back: an empty field reports its hint and a password field reports dots.
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
