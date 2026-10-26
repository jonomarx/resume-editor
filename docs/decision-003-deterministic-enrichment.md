# Decision 003: Deterministic (non-LLM) posting enrichment (US-109)

Status: proposed by the Job List Manager; the owner makes the final method choice at review.

## Problem

`PostingEnricher` (US-040) needs an LLM for every tag, requirement and eligibility field. Real LLM support is Epic 6, so until then every posting would be untagged and the Matcher (Epic 3) and cutdown scoring (Epic 4) would have nothing to work with.

## What was built

* `Enricher` interface; `PostingEnricher` (LLM) and `DeterministicEnricher` implement it. Persistence is shared (`EnrichmentStore`), so both write the same columns and tables.
* Selection: environment variable `ENRICHER` = `deterministic` (default) or `llm` (read as `app.jobs.enricher`, falling back to `${ENRICHER}`; an unknown value fails startup). `llm` with no `LlmClient` means no enrichment, as before.
* Tags only from the vocabulary (`TagVocabulary.list`, names plus aliases). No proposals are created by the deterministic enricher (see "Open decisions").
* Posting text is only read by regular expressions that are quoted literals between fixed lookarounds or simple anchored patterns; text is cut at 20,000 characters, section fields at 2,000, authorization at 120, class year at 40. A 500,000-character hostile input is tested to finish in well under the limit.

## Methods tried

| # | Method | Used for |
| --- | --- | --- |
| 1 | Naive: case-insensitive substring of every name and alias | baseline for tags |
| 2 | **A**, guarded vocabulary match. Unambiguous names (Java, Docker, `C++`, `C#`, `.NET`, `Node.js`...) match anywhere in the text with word boundaries that understand symbols. Ambiguous names (Go, R, C, Spring, Swift, Rust and a short list of ordinary words) count only inside the title, a requirement/preferred section or an explicit "Skills:/Tech stack:" line, only written exactly like the vocabulary, only with a skill-like context ("experience with", "languages:", a list comma next to another skill; single letters need a skill word), never before a year or calendar-like word ("Spring 2027", "Spring break", "Go to", "Go beyond"), and never when negated ("Rust is not required", "no Java experience"). A posting with no headings and no skills line uses its whole text as the zone | tags |
| 3 | The same guarded match with every name restricted to the title and the requirement sections/skills lines (whole text when the posting has none) | tags |
| 4 | Heading heuristics ("Requirements", "Qualifications", "What we are looking for", "Nice to have", "Preferred", inline "Requirements: ..."). A section ends at the next heading, at a prose line longer than 200 characters, or at a short unknown heading line that precedes a paragraph | `basic_requirements`, `preferred_requirements` (verbatim lines, empty when no heading) |
| 5 | Pattern rules | `season` (first "Summer 2027"-style mention that is not preceded by last/since/during/...), `class_year` (rising juniors/seniors, Class of 2028, graduating in 2028, graduation date between 2027 and 2028), `work_authorization` |
| 6 | Structured data (JSON-LD `JobPosting`, ATS fields) | not built as an enrichment method, see below |

**Work authorization is a normalized value, not a sentence:** `does_not_sponsor`, `sponsors`, `authorization_required`, or null when the posting says nothing. Sentences are judged separately; `does_not_sponsor` wins over `sponsors`, which wins over `authorization_required` (which covers "authorized to work", "eligible to work", "US citizen", "permanent resident"). "Security clearance" sentences are ignored. The LLM enricher still stores a free-text phrase in the same column; the owner should decide which form consumers (the Matcher's hard filters) expect.

**Safety.** All text goes through `PostingText.normalize` first (cut at 20,000 characters, runs of blanks collapsed, at most two consecutive newlines), every repetition in every pattern is bounded, nothing is a star over a group, and `CrawlService` and `IngestProcessor` also catch `StackOverflowError` so one posting can never break a run. `HostileInputTests` runs 24 hostile shapes (including the reviewer's `"rising juniors, " x 400` that overflowed the first version) through all rules and strategies with a 1 second bound each.

**Structured data.** By the time the enricher runs, the JSON-LD and ATS adapters (US-036 to US-038) have already turned structured fields into the stored `title`, `company`, `location` and `description` text (Lever's requirement lists arrive as a "Requirements:" heading in the description). The fields that would help (`skills`, `qualifications`, `experienceRequirements`, `educationRequirements`) appear in none of the existing JSON-LD or ATS fixtures, and the major ATS feeds (Greenhouse, Lever, Ashby) have no skills field. `employmentType` and `datePosted` are not enrichment fields. Using structured skills would need an adapter change plus a new column and is not worth it until a real source shows the data.

## Evaluation

Fixtures (`backend/jobs/src/test/resources/fixtures/enrichment/`), all labelled by hand before the numbers were read:

* `labelled-postings.json`: 16 short postings (100 to 400 characters), 56 expected tag links, a 26-tag vocabulary including `C`, `C++`, `C#`, `Go`, `R`, `Rust`, `Swift`, `.NET`, `Node.js`, written to contain traps.
* `labelled-postings-long.json`: 4 realistic long postings (4 to 5 KB: company blurbs, responsibilities, bullets, benefits, hiring process, EEO text, other teams' technologies, "Spring 2027", "Go beyond", "Spring break", "R&D", "C-suite", "Swift response times", "Rust is not required") with 22 expected links. They were added after the independent review found that the short set did not exercise common false-positive classes.

Reproduce with `mvn -q test -pl backend/jobs -am -Dtest=DeterministicEnrichmentEvaluationTests` (the tables are printed).

| Set | Method | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| short (16) | naive substring | 56 | 42 | 0 | 0.57 | 1.00 | 0.73 |
| short (16) | A guarded rules | 54 | 1 | 2 | 0.98 | 0.96 | 0.97 |
| short (16) | sections only | 50 | 0 | 6 | 1.00 | 0.89 | 0.94 |
| long (4) | naive substring | 22 | 22 | 0 | 0.50 | 1.00 | 0.67 |
| long (4) | A guarded rules | 22 | 2 | 0 | 0.92 | 1.00 | 0.96 |
| long (4) | sections only | 18 | 0 | 4 | 1.00 | 0.82 | 0.90 |
| both | A guarded rules | 76 | 3 | 2 | 0.96 | 0.97 | 0.97 |
| both | sections only | 68 | 0 | 10 | 1.00 | 0.87 | 0.93 |

Fields (exact match): requirement sections present or absent 28/28 (short) and 8/8 (long); season, class year and work authorization 15/15 (short) and 12/12 (long); no spurious values.

What the errors of A are: Kubernetes named in an "About" blurb, Kotlin named as another team's stack, "Security" from "the security team" in an embedded posting (company tech and unrelated mentions; unambiguous names match anywhere on purpose), `Go` named only in a responsibilities line (outside the zone) and `R` at the end of a "Languages: C, C++, C#, Go, R" line (a single letter needs a skill word next to it).

**Caveat.** The fixtures were written and labelled by the same agent that wrote the guards (the long set after the review, but with the same author), and 20 postings is a small sample, so the numbers are optimistic and the confidence intervals wide. Validation on 50 to 100 real stored postings is a condition for relying on them.

## Recommendation

Use method 2 (A: guarded match, whole text for unambiguous names, zone for ambiguous names) for tags, method 4 for requirements, method 5 for eligibility. That is what `ENRICHER=deterministic` does (`TagStrategy.FULL_TEXT`). Method 3 is implemented and one enum constant away if the owner prefers precision over recall; I do not recommend it because missing a tag silently removes a posting from matching (recall 0.87 against 0.97), while an extra tag only slightly dilutes it.

## Open decisions for the owner

1. **Proposals.** The deterministic enricher creates no tag proposals. Finding unknown skills in free text needs either an LLM or a guessing heuristic (capitalized tokens), which would flood the proposal queue with noise. Vocabulary growth stays manual (and LLM-assisted from Epic 6).
2. **Ambiguous-name list.** The short list of ambiguous names (`VocabularyTagMatcher.AMBIGUOUS`, plus every one- or two-letter name) is in code. A user who adds a tag called "Swift" is covered; a new ambiguous word ("Make", "Ray" are in) needs a code change. It could become a per-tag flag in a later contract story.
3. **Settings.** `ENRICHER` is read directly by the jobs module. The Backend role may want to name it in `application.yml` and the README settings list (not edited from the jobs role).
4. **Default.** `deterministic` is the default even when an LLM client exists (the card says default `deterministic`). With the fake LLM provider, the fake's canned answer is no longer used for enrichment unless `ENRICHER=llm`.
