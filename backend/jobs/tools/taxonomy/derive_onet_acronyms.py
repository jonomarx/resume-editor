#!/usr/bin/env python3 -I
"""Derives src/main/resources/taxonomy/onet-acronyms.json from the O*NET 31.0 database file software_skills.csv.

O*NET (USDOL/ETA) is licensed CC BY 4.0, see NOTICE-taxonomy.md. The raw file is untrusted input and is never committed:
fetch it into an empty directory outside the repository and pass its path.

  mkdir /somewhere/onet && curl -o /somewhere/onet/software_skills.csv \
      https://www.onetcenter.org/dl_files/database/db_31_0_csv/software_skills.csv
  python3 -I backend/jobs/tools/taxonomy/derive_onet_acronyms.py /somewhere/onet/software_skills.csv \
      > backend/jobs/src/main/resources/taxonomy/onet-acronyms.json

Method: O*NET "Workplace Example" entries often spell a name out and append its acronym ("Structured query language SQL").
When the initials of the words before an all-caps token spell that token, the pair (acronym, expansion) is recorded. Only
entries whose element class is a software-development class are kept. Nothing is executed; the file is only parsed as CSV.
"""
import csv
import json
import re
import sys

CLASS_WORDS = ("development", "data base", "database", "web", "operating system", "cloud", "object or component",
               "programming", "file versioning", "compiler", "network", "version")
MAX_ROWS = 200_000


def spells(words, token):
    """True if the words spell the acronym: each word supplies its first letter and possibly later letters of the same word
    ("Hypertext markup language" -> HTML, "JavaScript Object Notation" -> JSON), and every letter of the acronym is used."""
    words = [w.upper() for w in words if w[0].isalnum()]

    def sub(rest, word):  # rest is a subsequence of word[1:]
        it = iter(word[1:])
        return all(c in it for c in rest)

    def go(i, pos):
        if i == len(words):
            return pos == len(token)
        w = words[i]
        if pos >= len(token) or w[0] != token[pos]:
            return False
        return any(sub(token[pos + 1:pos + n], w) and go(i + 1, pos + n) for n in range(1, min(len(w), len(token) - pos) + 1))

    return go(0, 0)


def pairs(path):
    out = {}
    with open(path, encoding="utf-8-sig", newline="") as f:
        for n, row in enumerate(csv.DictReader(f)):
            if n >= MAX_ROWS:
                break
            element = (row.get("Element Name") or "").lower()
            if not any(w in element for w in CLASS_WORDS):
                continue
            words = re.sub(r"[()]", " ", row.get("Workplace Example") or "").split()
            for i, token in enumerate(words):
                if not re.fullmatch(r"[A-Z][A-Z0-9]{1,5}", token):
                    continue
                for k in range(2, 7):
                    if i - k < 0:
                        break
                    before = words[i - k:i]
                    if spells(before, token):
                        out.setdefault(token, set()).add(" ".join(before))
                        break
    return out


if __name__ == "__main__":
    found = pairs(sys.argv[1])
    doc = {
        "source": "O*NET 31.0 Database, software_skills.csv (USDOL/ETA), CC BY 4.0; derived and modified by this project",
        "acronyms": {a: sorted(e) for a, e in sorted(found.items())},
    }
    json.dump(doc, sys.stdout, indent=1, ensure_ascii=False)
    print()
