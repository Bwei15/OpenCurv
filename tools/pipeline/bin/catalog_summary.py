#!/usr/bin/env python3
"""
catalog_summary.py -- macht aus catalog.json eine Markdown-Tabelle fuer die
Job-Zusammenfassung von GitHub Actions.

Aufruf: catalog_summary.py <catalog.json>
"""

import json
import sys


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    c = json.load(open(argv[1]))

    print("| Region | Dateien | Groesse |")
    print("|---|---:|---:|")
    for r in c.get("regions", []):
        print("| %s | %d | %.1f MB |"
              % (r["name"], len(r["files"]), r["totalBytes"] / 1048576.0))
    for f in c.get("shared", []):
        print("| _(gemeinsam)_ %s | 1 | %.1f MB |"
              % (f["name"], f.get("bytes", 0) / 1048576.0))
    print("")
    print("**Gesamt: %.1f MB** &middot; Katalog-Schema %s &middot; Scorer `%s`"
          % (c.get("totalBytes", 0) / 1048576.0,
             c.get("schemaVersion"),
             c.get("producer", {}).get("scorer", "?")))

    # Geteilte Dateien sind der Ausnahmefall und muessen auffallen.
    split = [f["name"] for r in c.get("regions", []) for f in r["files"] if "split" in f]
    if split:
        print("")
        print("> Wegen des 2-GB-Limits geteilt: %s" % ", ".join(split))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
