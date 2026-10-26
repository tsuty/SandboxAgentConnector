# Throwaway environment for probe_capabilities.py: Node (`npx`), uv (`uvx`) and Python, nothing
# else. No credential is put in: only `initialize` is sent.
#
# trixie rather than bookworm: some binary agents need glibc 2.38 or newer (crow-cli).
FROM node:22-trixie-slim

# `uvx` for the uvx distributions of the registry.
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /usr/local/bin/

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY probe_capabilities.py /opt/probe/probe_capabilities.py

# Created here so the named volumes of compose.yaml start owned by `node` rather than root.
RUN mkdir -p /home/node/.npm /home/node/.cache/uv /home/node/agents && chown -R node:node /home/node

# The agents run as the unprivileged user of the image.
USER node
WORKDIR /work

ENTRYPOINT ["python3", "-I", "/opt/probe/probe_capabilities.py"]
CMD ["--help"]
