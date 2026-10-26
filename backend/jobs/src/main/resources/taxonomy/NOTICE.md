# Taxonomy data attribution

Each file here is derived by a script in `backend/jobs/tools/taxonomy/` from a source whose terms allow it. Raw dumps are never
committed; the scripts say how to fetch them into an empty directory outside the repository. All source text is untrusted.

* `wikidata-aliases.json`: English "also known as" labels of software items from Wikidata, which is dedicated to the public
  domain under CC0 1.0 (https://www.wikidata.org/wiki/Wikidata:Licensing). Derived by `derive_wikidata_aliases.py`, which uses
  the public query service politely (identifying User-Agent, sequential requests, pauses, a request cap).
* `linguist-aliases.json`: language aliases from GitHub Linguist `languages.yml`
  (https://github.com/github-linguist/linguist), MIT License, Copyright (c) 2017 GitHub, Inc. The MIT notice is
  "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
  files ... subject to the following conditions: The above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software." Derived by `derive_linguist_aliases.py`.
* `onet-aliases.json` (optional, secondary): acronym expansions derived from the O*NET 31.0 Database by the U.S. Department of
  Labor, Employment and Training Administration (USDOL/ETA), used under the CC BY 4.0 license
  (https://creativecommons.org/licenses/by/4.0/). O*NET is a trademark of USDOL/ETA. This project has modified the
  information (only acronym and spelled-out pairs, derived by `derive_onet_aliases.py`). USDOL/ETA has not approved,
  endorsed, or tested these modifications. See https://www.onetcenter.org/license_db.html.

Not used, by owner decision: Stack Overflow data, ESCO, Lightcast, LinkedIn, Indeed.
