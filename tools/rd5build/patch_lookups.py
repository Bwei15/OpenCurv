#!/usr/bin/env python3
"""
patch_lookups.py -- haengt den OpenCurv-Score-Tag an lookups.dat an.

Regel aus docs/developers/profile_developers_guide.md (Abschnitt
"Lookup-Table evolution"): Tags duerfen nur AM ENDE der jeweiligen
Kontext-Sektion angehaengt werden, Werte nur am Ende der Werteliste.
Dann genuegt eine Erhoehung der Minor-Version; die Major-Version
(die in die .rd5 geschrieben und beim Laden geprueft wird) bleibt gleich.

Zwei Varianten:
  --mode enum   opencurv:curve;0 0 / 1 / ... / 15   (16 diskrete Werte)
  --mode num    opencurv:curve;0 *                  (Wildcard, numerisch kodiert)

Aufruf: python3 patch_lookups.py <in-lookups.dat> <out-lookups.dat> --mode enum|num [--max 15]
"""

import sys

TAG_NAME = "opencurv:curve"
CONTEXT_END_MARKER = "---context:node"
MINOR_TAG = "---minorversion:"


def patch(src, dst, mode, maxval):
    with open(src, "r") as fh:
        lines = fh.read().split("\n")

    # Idempotent: the shipped app/src/main/assets/profiles/lookups.dat now
    # carries opencurv:curve itself (the production .brf profiles need it to
    # parse at all -- see 1.Doku/Cloud_Pipeline.md, section 3). If that file
    # is used as the --in here (build_rd5.sh does exactly that), patching it
    # a second time would append a second, conflicting opencurv:curve entry.
    # Detect that and copy through unchanged instead.
    if any(line.startswith(TAG_NAME + ";") for line in lines):
        with open(dst, "w") as fh:
            fh.write("\n".join(lines))
        print("%s bereits vorhanden in %s -- unveraendert nach %s kopiert" % (TAG_NAME, src, dst))
        return

    if mode == "enum":
        block = ["", "# OpenCurv: vorberechneter Kurven-/Fahrspass-Score, 0..%d" % maxval]
        block += ["%s;0000000001 %d" % (TAG_NAME, v) for v in range(0, maxval + 1)]
    elif mode == "num":
        block = ["", "# OpenCurv: vorberechneter Kurven-/Fahrspass-Score, numerisch",
                 "%s;0000000001 *" % TAG_NAME]
    else:
        raise SystemExit("unknown mode: " + mode)

    out, inserted, bumped = [], False, False
    for line in lines:
        if line.startswith(MINOR_TAG) and not bumped:
            out.append(MINOR_TAG + str(int(line[len(MINOR_TAG):].strip()) + 1))
            bumped = True
            continue
        if line.startswith(CONTEXT_END_MARKER) and not inserted:
            out.extend(block)
            out.append("")
            inserted = True
        out.append(line)

    if not inserted:
        raise SystemExit("marker %s not found -- lookups.dat layout changed?" % CONTEXT_END_MARKER)

    with open(dst, "w") as fh:
        fh.write("\n".join(out))
    print("patched %s -> %s (mode=%s, minorversion bumped=%s)" % (src, dst, mode, bumped))


if __name__ == "__main__":
    args = sys.argv[1:]
    mode = "enum"
    maxval = 15
    if "--mode" in args:
        mode = args[args.index("--mode") + 1]
    if "--max" in args:
        maxval = int(args[args.index("--max") + 1])
    pos = [a for a in args if not a.startswith("--") and a not in (mode, str(maxval))]
    if len(pos) < 2:
        print(__doc__)
        sys.exit(1)
    patch(pos[0], pos[1], mode, maxval)
