# Decision 005: Published date (postedAt), US-116

`job_posting.posted_at` (nullable, migration `V20261026120000`) holds when the employer published the posting. It is exposed
through the `PostingReader` port as `PostingView.postedAt`.

**Where it comes from (never guessed; otherwise null)**

| Source | Field used | Note |
| --- | --- | --- |
| JSON-LD `JobPosting` | `datePosted` | ISO date or date-time |
| Greenhouse | `first_published` | `updated_at` is the last edit, not publication, so it is no longer used as a fallback |
| Lever | `createdAt` (epoch ms) | when the posting was created on the board |
| Ashby | `publishedAt` | |
| LLM extraction | `datePosted` | validated like the others |
| Pasted or crawled text | an explicit "Posted on 2026-10-01", "Date posted: Oct 1, 2026", "Published on ..." line, only when the source gave no date | other dates ("Apply by", "Start date") are ignored |

`PostedDates.parse`: ISO date, offset date-time or local date-time (read as UTC); a date without a time is midnight UTC. Null for
unparseable values, dates before 2000, and dates more than one day in the future (clock-skew allowance).

**Update rule.** On every crawl that sees the posting the earliest credible date wins: a later date from a repost never makes an
old posting look new; an earlier one replaces a later one; a missing date keeps the stored one; a date appearing later fills
an empty column. (The card says "unless the source's own date changes"; I read that as covered by "earliest wins" because a source
that corrects its date to an earlier one is taken. A source moving its date later is treated as a repost. The owner may want to
change this.) Ingested postings are stored once and never re-read.

**Freshness and "newest".** `maxAgeDays` is applied by core through `PostingQuery.firstSeenSince`, which by the port's contract
filters on `first_seen_at` only; core's "newest" sort reads `PostingView.postedAt` and falls back to `firstSeenAt`. To make the
freshness filter use the published date with a first-seen fallback the port needs a criterion (for example an effective date
`COALESCE(posted_at, first_seen_at)`), which is a `backend/common` contract change: a role:backend contract card was opened.
