# Decision 004: Tag-generation contenders A, B and C (US-109, US-114, US-115)

Owner decision (2026-10-26): build all three deterministic tag-generation contenders and compare them. This record collects
the comparison; decision 003 describes contender A in detail. The default stays A until the owner decides.

* **A**: guarded vocabulary rules (US-109).
* **B**: A plus aliases from open taxonomies (US-114, this section).
* **C**: classical machine learning (US-115, added by that story).

Selection: `ENRICHER_TAGS` (`app.jobs.enricher.tags`) = `rules` (A, default), `rules-sections`, `taxonomy` (B), `taxonomy-onet`
(B plus the optional O*NET source).

## Contender B: taxonomy aliases

### Sources and licences (checked before use)

| Source | Licence | Used for | Derived file |
| --- | --- | --- | --- |
| Wikidata (query service, English altLabels of software items) | CC0 1.0 | primary | `wikidata-aliases.json` (43 keys, 91 aliases) |
| GitHub Linguist `languages.yml` | MIT, Copyright (c) 2017 GitHub, Inc. | primary | `linguist-aliases.json` (18 keys, 36 aliases) |
| O*NET 31.0 `software_skills.csv` (acronym and spelled-out pairs) | CC BY 4.0 (attribution required, "modified" stated) | optional, secondary | `onet-aliases.json` (147 keys) |

Not used, by owner decision: Stack Overflow, ESCO, Lightcast, LinkedIn, Indeed. Attribution text is in
`backend/jobs/src/main/resources/taxonomy/NOTICE.md`.

### How the data is derived and used

* `backend/jobs/tools/taxonomy/` holds one script per source plus `candidate-tags.txt` (110 popular technology names) and a
  shared `alias_filter.py`. Raw data is fetched into an empty directory outside the repository, treated as untrusted (parsed as
  CSV, JSON or by a two-shape line parser, never loaded with a general YAML loader, never executed), and only the small derived
  file is committed. The Wikidata script makes at most 12 sequential requests with a pause and an identifying User-Agent.
* Filter: printable ASCII, 2 to 40 characters, no markup characters, namespaces or parentheses ("Extension:AWS", "Python
  (language)"), no leading dot, no 1-2 letter lowercase aliases ("rb", "tf", "es"), never the tag's own name or another candidate.
* At run time a tag receives the aliases of a key only when its own name or one of its aliases equals that key (case-insensitive),
  so aliases attach only to tags the user already has and no tag is created. An alias equal to any name or alias already in the
  user's vocabulary is not added (it cannot steal). Short single-word taxonomy aliases (4 letters or fewer) get the ambiguous-name
  treatment of A (title, requirement section or skills list only, exact case, skill cue). Files are capped in size and entry
  count when read, and loaded once.

### Evaluation

Same harness as A (`DeterministicEnrichmentEvaluationTests`). Three labelled sets, all labelled before B was run:
the 16 short and 4 long postings of decision 003, and a new set of 17 alias-style and paraphrased postings
(`labelled-postings-aliased.json`, 39 expected links; vocabulary users have not given aliases such as "Amazon Web Services" or
"Structured Query Language"; 3 of the 17 are paraphrases no alias list can solve, such as "neural networks" for Machine Learning).

| Set | Method | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| short (16) | A | 54 | 1 | 2 | 0.98 | 0.96 | 0.97 |
| short (16) | B (Wikidata + Linguist) | 54 | 1 | 2 | 0.98 | 0.96 | 0.97 |
| short (16) | B+ (plus O*NET) | 54 | 1 | 2 | 0.98 | 0.96 | 0.97 |
| long (4) | A | 22 | 2 | 0 | 0.92 | 1.00 | 0.96 |
| long (4) | B | 22 | 2 | 0 | 0.92 | 1.00 | 0.96 |
| long (4) | B+ | 22 | 2 | 0 | 0.92 | 1.00 | 0.96 |
| aliased (17) | naive substring | 30 | 53 | 9 | 0.36 | 0.77 | 0.49 |
| aliased (17) | A | 29 | 3 | 10 | 0.91 | 0.74 | 0.82 |
| aliased (17) | B | 29 | 3 | 10 | 0.91 | 0.74 | 0.82 |
| aliased (17) | B+ | 35 | 3 | 4 | 0.92 | 0.90 | 0.91 |

### What this shows

* The two primary sources (Wikidata, Linguist) added nothing measurable on these fixtures and cost nothing: their aliases are
  mostly name variants that A already matches ("Python programming language" contains "Python"), a few spelled-out forms
  ("Common Business-Oriented Language", "Extensible Markup Language"), and a few real short forms ("K8s", "NodeJS", "NextJS",
  "sklearn", "Golang", "cpp"), most of which users add themselves. Several entries are noise ("Microsoft Java" for C#, "Pearl" for
  Perl, "cake" for C# in Linguist), contained by the ambiguity guards but unhelpful.
* The O*NET expansions are what moved the aliased set (recall 0.74 to 0.90): "Amazon Web Services" for AWS, "Structured query
  language" for SQL, "Hypertext markup language" for HTML, "Cascading style sheets" for CSS, "JavaScript Object Notation" for JSON
  (which also removes no false positive: the posting still tags JavaScript, because "JavaScript Object Notation" contains
  "JavaScript"). Example wins (B+ over A): al-aws, al-sql, al-web, al-dotnet. Example losses shared by A, B and B+: "Postgres" for
  PostgreSQL (no source lists it), "Hypertext Preprocessor" for PHP, "neural networks" for Machine Learning, "container
  orchestration" for Kubernetes.
* Because the aliased set was written to contain spelled-out names and the labels preceded the sources, the 0.74 to 0.90 gain is
  an upper bound for how common that wording is. Real postings mostly use the short forms.
* Precision never dropped. The three false positives are the same for A, B and B+.

### Recommendation

Keep A as the default. If the owner wants alias help, B+ (`taxonomy-onet`) is the useful variant; the cost is one more CC BY
attribution. The primary sources alone are not worth switching on. The better alias source for this vocabulary is the user's
own: a dozen aliases added in the tag editor ("Amazon Web Services", "Postgres") do more than any open taxonomy found so far.

## Contender C: classical machine learning (US-115)

Selection: `ENRICHER_TAGS=ml`. Built on the US-114 branch (it reuses the taxonomy aliases for weak supervision, the `Enricher`
interface and the evaluation harness).

### What it is

* **Library: Apache OpenNLP 2.5.4** (`opennlp-tools`, Apache License 2.0). Reason for the dependency: a pure-Java maximum-entropy
  sequence tagger (NameFinder, BIO spans) with its training API in the same jar; no native code, no GPU, deterministic inference
  from a small model file. Tribuo was considered (CRF) and rejected as a much larger dependency for the same result at this size.
* **Model:** one entity type "skill". Committed as `backend/jobs/src/main/resources/ml/skill-spans.bin`, about 112 KB.
* **Inference:** `SkillTokenizer` (bounded: 20,000 characters, 6,000 tokens, 120 tokens per sentence; keeps `C++`, `C#`, `.NET`,
  `Node.js` whole) then `SkillSpanModel` (one `NameFinderME` per call, no shared state) then `SkillLinker`, which links a span to
  a tag the user already has: exact match on name or alias (the open-taxonomy aliases of B included), else a close spelling
  (edit-distance similarity at least 0.85, 6+ characters, same first letter, "Kuberntes" for "Kubernetes"). Unknown spans (a
  skill the vocabulary lacks, such as Terraform) are returned by `MlTagger` but never stored and never proposed.
* **Training (reproducible):** `SkillCorpus` generates 9,000 sentences from about 50 posting-style templates plus 40 trap
  sentences (ambiguous words used as ordinary words), inserting names and taxonomy aliases of the 110 candidate technologies at
  known positions, with a fixed seed (20261026). A sentence is kept only when the rules matcher (A with taxonomy aliases) finds
  exactly the inserted tags in it: the labels are the matcher's (weak supervision). Maximum-entropy GIS, 100 iterations, cutoff 3,
  single thread: no randomness. Regenerate with
  `mvn -q test -pl backend/jobs -am -Dtest=SkillTaggerTests -DargLine="-Dml.write=$PWD/backend/jobs/src/main/resources/ml/skill-spans.bin"`.
  A test retrains twice and checks the committed model gives the same spans.
* **No leakage:** the corpus generator skips any sentence equal to a sentence of the evaluation fixtures, and a separate test
  fails if any evaluation sentence (all three sets) occurs in the training data.

### Licences and data not used

* OpenNLP: Apache 2.0. No dataset is downloaded or committed for C.
* SkillSpan (Zhang et al., 2022; Hugging Face `jjzha/skillspan`): the card says CC BY 4.0, but its "tech" part consists of Stack
  Overflow job ads, a source the owner excluded, and its other part is non-technical jobs; not used. Green et al. (2022): not used
  (no clearly permissive redistributable data found). If the owner approves a corpus, it is a drop-in: add annotated spans to
  `SkillCorpus`. **Limitation that follows:** there is no real unlabelled posting corpus in the repository, so the model has only
  seen generated text. That is the main reason it generalizes poorly.

### Results (same harness; precision / recall / F1)

| Set | A | B | B+ | C | A + C (union) |
| --- | --- | --- | --- | --- | --- |
| short (16) | 0.98 / 0.96 / 0.97 | 0.98 / 0.96 / 0.97 | 0.98 / 0.96 / 0.97 | 0.96 / 0.84 / 0.90 | 0.97 / 1.00 / 0.98 |
| long (4) | 0.92 / 1.00 / 0.96 | 0.92 / 1.00 / 0.96 | 0.92 / 1.00 / 0.96 | 0.68 / 0.86 / 0.76 | 0.69 / 1.00 / 0.81 |
| aliased (17) | 0.91 / 0.74 / 0.82 | 0.91 / 0.74 / 0.82 | 0.92 / 0.90 / 0.91 | 0.97 / 0.82 / 0.89 | 0.92 / 0.90 / 0.91 |
| short + long (78 links) | 0.96 / 0.97 / 0.97 | 0.96 / 0.97 / 0.97 | 0.96 / 0.97 / 0.97 | 0.86 / 0.85 / 0.85 | 0.87 / 1.00 / 0.93 |

C inference: about 11 ms per 4-5 KB posting on this machine (averaged over 80 runs, including the per-call linker setup; the first
call also loads the model once); model size 112 KB; deterministic.

### Examples

* C wins: `sec` ("Review code in Python and Go" outside a requirement section: C finds Go, A's zone rule misses it); `tricky-lists`
  (R at the end of a "Languages:" line); in the aliased set C beats A and B on spelled-out names that the taxonomy links
  ("Amazon Web Services", "Structured Query Language", "Hypertext Markup Language") and finds spans A cannot see.
* C loses: the long postings. It tags Go, Spring, Swift and Rust from ordinary prose ("Go to the weekly reading group", "Spring
  break", "Swift response times", "Rust is not required") that A's zone, case, year and negation guards reject, giving 9 false
  positives on 4 postings. It misses role-type and domain tags (Backend, Frontend, Data Science, Embedded) that the templates
  rarely show, and `Docker/Kubernetes` written with a slash is one token to the tokenizer so it links nothing.
* The union A + C reaches recall 1.00 on the short and long sets at the price of C's false positives.

### Recommendation

Keep A as the default. C does not beat rules on these fixtures; it is a sentence-level model without section awareness, trained on
generated text, so it loses the guards A gets for free. It is worth revisiting only with real annotated postings (the code is the
same, the corpus changes). If the owner wants more recall than A, A + B+ costs far less than C.
