# LaTeX renderer: TeX Live plus a tiny HTTP wrapper. Run without network access
# (see docker-compose.yml: internal network only), as a non-root user, read-only rootfs.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 \
      texlive-latex-base texlive-latex-recommended texlive-latex-extra \
      texlive-fonts-recommended texlive-fonts-extra \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home renderer

COPY app/server.py /opt/renderer/server.py

# Restricted file access: pdflatex may only open files below the working directory (paranoid mode),
# and shell escape is also disabled on the command line.
ENV openin_any=p openout_any=p shell_escape=f
ENV RENDER_TIMEOUT_SECONDS=20 RENDER_MAX_BYTES=1000000 PORT=8080

USER 10001
EXPOSE 8080
CMD ["python3", "/opt/renderer/server.py"]
