#!/usr/bin/env python3 -I
"""Derives src/main/resources/taxonomy/linguist-aliases.json from GitHub Linguist's languages.yml.

Linguist is MIT licensed (Copyright (c) 2017 GitHub, Inc.), see NOTICE.md. The raw file is untrusted input and is never
committed. Fetch it into an empty directory outside the repository and pass its path:

  mkdir /somewhere/linguist && curl -o /somewhere/linguist/languages.yml \
      https://raw.githubusercontent.com/github-linguist/linguist/main/lib/linguist/languages.yml
  python3 -I backend/jobs/tools/taxonomy/derive_linguist_aliases.py /somewhere/linguist/languages.yml \
      > backend/jobs/src/main/resources/taxonomy/linguist-aliases.json

The YAML is read by a tiny line parser for the two shapes used (a top-level "Name:" key and an "aliases:" list), never by
a general YAML loader. Only languages whose name is in candidate-tags.txt are kept.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from alias_filter import clean, read_candidates  # noqa: E402

MAX_LINES = 200_000


def parse(path):
    langs, name, in_aliases = {}, None, False
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f):
            if n > MAX_LINES:
                break
            line = line.rstrip("\n")
            if line and not line[0].isspace() and line.endswith(":") and not line.startswith("#"):
                name = line[:-1].strip("\"'")
                langs[name] = []
                in_aliases = False
            elif name and line.startswith("  aliases:"):
                in_aliases = True
            elif name and in_aliases and line.startswith("  - "):
                langs[name].append(line[4:].strip().strip("\"'"))
            elif name and in_aliases and not line.startswith("  - "):
                in_aliases = False
    return langs


if __name__ == "__main__":
    candidates = read_candidates(os.path.join(os.path.dirname(os.path.abspath(__file__)), "candidate-tags.txt"))
    cand_lower = {c.lower() for c in candidates}
    langs = parse(sys.argv[1])
    out = {}
    for name, aliases in langs.items():
        if name.lower() not in cand_lower:
            continue
        kept = sorted({a for a in (clean(x, name.lower(), cand_lower) for x in aliases) if a})
        if kept:
            out[name.lower()] = kept
    json.dump({"source": "GitHub Linguist languages.yml (MIT, Copyright (c) 2017 GitHub, Inc.); aliases only, derived by this project",
               "licence": "MIT", "aliases": dict(sorted(out.items()))}, sys.stdout, indent=1)
    print()
