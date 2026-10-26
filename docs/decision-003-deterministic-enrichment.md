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
| 2 | Guarded vocabulary match over the whole text: word boundaries that understand `C++`, `C#`, `.NET`, `Node.js`; ambiguous names (single letters and words such as Go, R, C, Swift, Rust, Spring) only count when written exactly like the vocabulary and used in a skill-like context ("experience with", a list comma, "in", "or" and so on) | tags |
| 3 | The same guarded match, restricted to the title and the requirement/preferred sections (found by heading heuristics); whole text when the posting has no such heading | tags |
| 4 | Heading heuristics ("Requirements", "Qualifications", "What we're looking for", "Nice to have", "Preferred", and inline "Requirements: ...") | `basic_requirements`, `preferred_requirements` (verbatim lines, empty when no heading) |
| 5 | Pattern rules | `season` ("Summer 2027", year required), `class_year` ("rising juniors and seniors", "Class of 2028", "graduating in 2028"), `work_authorization` (the sentence mentioning authorization, sponsorship, citizenship, clearance) |
| 6 | Structured data (JSON-LD `JobPosting`, ATS fields) | not built as an enrichment method, see below |

**Structured data.** By the time the enricher runs, the JSON-LD and ATS adapters (US-036 to US-038) have already turned structured fields into the stored `title`, `company`, `location` and `description` text (Lever's requirement lists arrive as a "Requirements:" heading in the description). The fields that would help (`skills`, `qualifications`, `experienceRequirements`, `educationRequirements`) appear in none of the existing JSON-LD or ATS fixtures, and the major ATS feeds (Greenhouse, Lever, Ashby) have no skills field. `employmentType` and `datePosted` are not enrichment fields. Using structured skills would need an adapter change plus a new column and is not worth it until a real source shows the data; the heading heuristics already capture what Lever structures.

## Evaluation

Fixture: `backend/jobs/src/test/resources/fixtures/enrichment/labelled-postings.json`, 16 hand-labelled postings (ATS-style, JSON-LD-style and pasted text) and a 26-tag vocabulary including `C`, `C++`, `C#`, `Go`, `R`, `Rust`, `Swift`, `.NET`, `Node.js`, with traps ("Go to our careers page", "go-getter", "Go-to-market", "R&D", "C-level", "ASP.NET", "Swift-moving"). 56 expected tag links. Reproduce with `mvn -q test -pl backend/jobs -am -Dtest=DeterministicEnrichmentEvaluationTests` (the table is printed).

Tags:

| Method | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 naive substring | 56 | 42 | 0 | 0.57 | 1.00 | 0.73 |
| 2 guarded, whole text | 56 | 2 | 0 | 0.97 | 1.00 | 0.98 |
| 3 guarded, requirement sections | 48 | 1 | 8 | 0.98 | 0.86 | 0.91 |

Fields (exact match against labels): requirement sections present or absent 28/28; season 5/5; class year 5/5; work authorization 5/5; no spurious values on postings that do not state them.

What the errors are:

* Method 2 false positives: Kubernetes named in an "About Acme" blurb (company tech, not the role), and "Security" from the line "Security clearance is not required" (a domain word used in an eligibility sentence).
* Method 3 loses tags named outside a requirement heading (responsibilities, an unheaded "Stack:" line, prose in a posting with no headings falls back to whole text). It gains almost no precision (one fewer false positive) at a recall cost of 14 points. The "Security clearance" false positive survives scoping.
* Rules never produced a wrong value on the fixtures, but they are not exhaustive: unusual phrasing is simply left empty.

**Caveat.** The fixtures were written and labelled by the same agent that tuned the guards, so the numbers are optimistic. They show that the guards work and where the methods trade off, not a production accuracy. Before relying on them, run both on 50 to 100 real stored postings and read the diffs; the evaluation test makes that cheap (point a loader at an export).

## Recommendation

Use method 2 (guarded vocabulary match over the whole text) for tags, method 4 for requirements, method 5 for eligibility. That is what `ENRICHER=deterministic` does (`TagStrategy.FULL_TEXT`). Method 3 is implemented and one enum constant away (`TagStrategy.SECTION_SCOPED`, tested) if the owner prefers precision over recall; I do not recommend it because missing a tag silently removes a posting from matching, while an extra tag only slightly dilutes it.

## Open decisions for the owner

1. **Proposals.** The deterministic enricher creates no tag proposals. Finding unknown skills in free text needs either an LLM or a guessing heuristic (capitalized tokens), which would flood the proposal queue with noise. Vocabulary growth stays manual (and LLM-assisted from Epic 6).
2. **Ambiguous-name list.** The short list of ambiguous names (`VocabularyTagMatcher.AMBIGUOUS`, plus every one- or two-letter name) is in code. A user who adds a tag called "Swift" is covered; a new ambiguous word ("Make", "Ray" are in) needs a code change. It could become a per-tag flag in a later contract story.
3. **Settings.** `ENRICHER` is read directly by the jobs module. The Backend role may want to name it in `application.yml` and the README settings list (not edited from the jobs role).
4. **Default.** `deterministic` is the default even when an LLM client exists (the card says default `deterministic`). With the fake LLM provider, the fake's canned answer is no longer used for enrichment unless `ENRICHER=llm`.
