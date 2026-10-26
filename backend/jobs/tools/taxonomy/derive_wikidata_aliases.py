#!/usr/bin/env python3 -I
"""Derives src/main/resources/taxonomy/wikidata-aliases.json: English "also known as" aliases of software items on Wikidata.

Wikidata data is CC0 (public domain dedication). The response is untrusted input: it is parsed as JSON only, filtered by
alias_filter.clean, and never committed raw. This script talks to the public query service politely: one identifying
User-Agent, sequential requests, a pause between them, small label batches, a timeout, and a hard cap on the number of
requests. Run it by hand, rarely; the derived file is committed.

  python3 -I backend/jobs/tools/taxonomy/derive_wikidata_aliases.py > backend/jobs/src/main/resources/taxonomy/wikidata-aliases.json

An item is used only when its English label equals a name in candidate-tags.txt and it is (a subclass-closure) instance of
programming language, software library, software framework, web framework, database management system, markup language,
query language or software. Its English altLabels become the aliases.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from alias_filter import clean, read_candidates  # noqa: E402

ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "resume-editor-alias-derivation/0.1 (https://github.com/jonomarx/resume-editor)"
CLASSES = "wd:Q9143 wd:Q188860 wd:Q271680 wd:Q1330336 wd:Q176165 wd:Q37045 wd:Q845739 wd:Q7397"
BATCH = 25
MAX_REQUESTS = 12
PAUSE_SECONDS = 5


def query(labels):
    values = " ".join('"%s"@en' % l.replace("\\", "").replace('"', "") for l in labels)
    q = f"""SELECT ?label ?alias WHERE {{
      VALUES ?label {{ {values} }}
      VALUES ?cls {{ {CLASSES} }}
      ?item rdfs:label ?label .
      ?item wdt:P31/wdt:P279* ?cls .
      ?item skos:altLabel ?alias . FILTER(LANG(?alias) = "en")
    }}"""
    url = ENDPOINT + "?" + urllib.parse.urlencode({"query": q, "format": "json"})
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read(5_000_000))["results"]["bindings"]


if __name__ == "__main__":
    candidates = read_candidates(os.path.join(os.path.dirname(os.path.abspath(__file__)), "candidate-tags.txt"))
    cand_lower = {c.lower() for c in candidates}
    found = {}
    requests = 0
    for i in range(0, len(candidates), BATCH):
        if requests >= MAX_REQUESTS:
            break
        if requests:
            time.sleep(PAUSE_SECONDS)
        requests += 1
        for b in query(candidates[i:i + BATCH]):
            label, alias = b["label"]["value"], b["alias"]["value"]
            a = clean(alias, label.lower(), cand_lower)
            if a:
                found.setdefault(label.lower(), set()).add(a)
    json.dump({"source": "Wikidata (CC0) English altLabels of software items; derived by this project", "licence": "CC0",
               "aliases": {k: sorted(v) for k, v in sorted(found.items())}}, sys.stdout, indent=1)
    print()
