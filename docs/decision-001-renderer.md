# Decision record 001: LaTeX renderer (US-012)

Status: accepted (2026-10-18)

## Decision

`renderer/` holds a Debian bookworm image with Debian's TeX Live 2022 packages
(`texlive-latex-base`, `-latex-recommended`, `-latex-extra`, `-fonts-recommended`, `-fonts-extra`)
and a stdlib-only Python wrapper (`renderer/app/server.py`).

API (internal only): `POST /render` with the LaTeX source as the body.
`200 application/pdf` with `X-Page-Count`; `422 {"log"}` on a compile error; `413` over the size limit;
`504` when the timeout kills the compile; `GET /health`. The wrapper is stateless and compiles in a fresh
temp directory per request, always removed afterward.

## Sandbox

| Control | How |
| --- | --- |
| No network | Compose puts it on an `internal: true` network (no route out, no external DNS). The app joins that network to call it. |
| Shell escape off | `pdflatex -no-shell-escape` and `shell_escape=f`. Tested with `\write18` and `\input{\|...}`. |
| Restricted file access | `openin_any=p`, `openout_any=p` (paranoid): no absolute paths or `..`. Tested by `\input{/etc/hostname}`. |
| Hard timeout | `RENDER_TIMEOUT_SECONDS` (default 20); the whole process group gets SIGKILL. |
| Size limit | `RENDER_MAX_BYTES` (default 1000000) returns 413 before reading the body. |
| Container | non-root uid 10001, read-only rootfs, tmpfs `/work`, all capabilities dropped, no-new-privileges, pids and memory limits. |

Tests: `renderer/tests/test.sh` (needs Docker).

## Spike: Jake's template compiles unmodified

`renderer/tests/fixtures/jake.tex` is a byte copy of `docs/reference/jake-resume-template.tex` (Jake Gutierrez's
template, MIT, with its sample content), unmodified. It compiles in this image to a one-page PDF with
`glyphtounicode` and `marvosym` available. No extra packages were needed beyond the list above. Keep the
two copies identical.

## Notes and trade-offs

- Debian packages give a pinned TeX Live 2022; upgrade by changing the base image tag. The full
  `texlive/texlive` image is much larger and was not needed.
- `texlive-fonts-extra` (about 3 GB image in total) is large but is what supplies `marvosym`; trim later if image size matters.
- The wrapper returns the raw log; mapping errors to resume nodes is US-013.
