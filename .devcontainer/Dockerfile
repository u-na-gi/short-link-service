# syntax=docker/dockerfile:1
FROM debian:trixie

ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
# sbt launcher version. The sbt version that actually runs is set by project/build.properties
ARG SBT_VERSION=1.13.0
# runn (the E2E scenario runner for tests/) version
ARG RUNN_VERSION=1.11.0
# Versions of metals-mcp (Metals' MCP server) and coursier, which installs it
ARG METALS_VERSION=1.6.9
ARG COURSIER_VERSION=2.1.25
# aqua (version manager for infra CLIs) version. The versions of the managed CLIs are set by /app/aqua.yaml
ARG AQUA_VERSION=2.63.0

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# --- base packages + JDK ------------------------------------------------------
# Pin the JDK to 21. Play 3.0 supports Java LTS 11/17/21; 25 is not supported
# (https://www.playframework.com/documentation/3.0.x/Requirements)
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg \
        git openssh-client \
        sudo less procps locales vim unzip jq \
        make \
        direnv \
        openjdk-21-jdk-headless \
        openjdk-21-source \
    && sed -i 's/# en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen \
    && locale-gen \
    && rm -rf /var/lib/apt/lists/*

# --- sbt ----------------------------------------------------------------------
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
        | tar xzf - -C /usr/share \
    && chown -R root:root /usr/share/sbt \
    && chmod -R 755 /usr/share/sbt \
    && ln -s /usr/share/sbt/bin/sbt /usr/local/bin/sbt

# --- metals-mcp ---------------------------------------------------------------
# Metals' MCP server, which Claude Code starts over stdio from /app/.mcp.json.
# coursier is used only to install it and is not left behind. cs install writes a launcher script;
# the actual jar is downloaded to ~/.cache/coursier on first launch
RUN curl -fsSL "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/cs-x86_64-pc-linux.gz" \
        | gzip -d > /tmp/cs \
    && chmod +x /tmp/cs \
    && /tmp/cs install --install-dir /usr/local/bin "metals-mcp:${METALS_VERSION}" \
    && rm -rf /tmp/cs /root/.cache/coursier

# --- GitHub CLI ---------------------------------------------------------------
# Credentials: compose.yaml bind-mounts the host's ~/.config/gh
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

# --- runn ---------------------------------------------------------------------
# Runs the E2E scenarios in tests/. It is a single Go binary, so just extract and place it
RUN arch="$(dpkg --print-architecture)" \
    && curl -fsSL "https://github.com/k1LoW/runn/releases/download/v${RUNN_VERSION}/runn_v${RUNN_VERSION}_linux_${arch}.tar.gz" \
        | tar xzf - -C /usr/local/bin runn \
    && chmod 0755 /usr/local/bin/runn

# --- aqua ---------------------------------------------------------------------
# terraform / tflint / ecspresso / aws cli are pinned in aqua.yaml and installed through aqua.
# The entries in aqua's bin are links that call aqua itself, so aqua itself must be on PATH too
RUN arch="$(dpkg --print-architecture)" \
    && curl -fsSL "https://github.com/aquaproj/aqua/releases/download/v${AQUA_VERSION}/aqua_linux_${arch}.tar.gz" \
        | tar xzf - -C /usr/local/bin aqua \
    && chmod 0755 /usr/local/bin/aqua

# --- Node.js (LTS) ------------------------------------------------------------
# setup_lts.x resolves to the Active LTS line at that time on the NodeSource side
RUN curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# --- dev user -----------------------------------------------------------------
# debian:trixie has no user with uid/gid 1000, so we can create it directly
RUN groupadd --gid ${USER_GID} ${USERNAME} \
    && useradd --uid ${USER_UID} --gid ${USER_GID} -m -s /bin/bash ${USERNAME} \
    && echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${USERNAME} \
    && chmod 0440 /etc/sudoers.d/${USERNAME}

USER ${USERNAME}
WORKDIR /app

# --- named volume mount points ---------------------------------------------------
RUN mkdir -p \
        /home/${USERNAME}/.sbt \
        /home/${USERNAME}/.ivy2 \
        /home/${USERNAME}/.cache/coursier \
        /home/${USERNAME}/.claude \
        /home/${USERNAME}/.local/share/aquaproj-aqua


RUN mkdir -p /home/${USERNAME}/.config/gh /home/${USERNAME}/.aws

# --- Claude Code -------------------------------------------------------------------
RUN curl -fsSL https://claude.ai/install.sh | bash
ENV PATH="/home/${USERNAME}/.local/bin:${PATH}"

# --- Antigravity CLI (agy) ----------------------------------------------------
# Google's official installer. Installs to ~/.local/bin/agy and then updates itself while running.
# Auth and settings persist in ~/.gemini (a named volume in compose.yaml)
RUN mkdir -p /home/${USERNAME}/.gemini \
    && curl -fsSL https://antigravity.google/cli/install.sh | bash

RUN printf '\n# custom aliases\nalias ccl='\''claude --dangerously-skip-permissions'\''\n' \
        >> /home/${USERNAME}/.bashrc

# --- aqua PATH and direnv hook ---------------------------------------------------
ENV AQUA_ROOT_DIR="/home/${USERNAME}/.local/share/aquaproj-aqua"
ENV PATH="${AQUA_ROOT_DIR}/bin:${PATH}"
RUN printf '\n# direnv\neval "$(direnv hook bash)"\n' >> /home/${USERNAME}/.bashrc

# --- bun ----------------------------------------------------------------------
# Official installer. Installs to ~/.bun/bin
RUN curl -fsSL https://bun.sh/install | bash
ENV BUN_INSTALL="/home/${USERNAME}/.bun"
ENV PATH="${BUN_INSTALL}/bin:${PATH}"
