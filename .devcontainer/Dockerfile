# syntax=docker/dockerfile:1
FROM debian:trixie

ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
# sbt ランチャーの版。実際に走る sbt の版は project/build.properties が決める
ARG SBT_VERSION=1.13.0
# runn (tests/ の E2E シナリオランナー) の版
ARG RUNN_VERSION=1.11.0
# metals-mcp (Metals の MCP サーバ) と、それを入れる coursier の版
ARG METALS_VERSION=1.6.9
ARG COURSIER_VERSION=2.1.25
# aqua (インフラ用 CLI の版管理) の版。管理する CLI の版は /app/aqua.yaml が決める
ARG AQUA_VERSION=2.63.0

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# --- base packages + JDK ------------------------------------------------------
# JDK は 21 に固定。Play 3.0 のサポート対象は Java LTS 11/17/21 で 25 は対象外
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
# Claude Code が /app/.mcp.json から stdio で起動する Metals の MCP サーバ。
# coursier は入れるためだけに使い、置いて行かない。cs install が書くのは起動スクリプトで、
# 本体の jar は初回起動時に ~/.cache/coursier へ落ちる
RUN curl -fsSL "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/cs-x86_64-pc-linux.gz" \
        | gzip -d > /tmp/cs \
    && chmod +x /tmp/cs \
    && /tmp/cs install --install-dir /usr/local/bin "metals-mcp:${METALS_VERSION}" \
    && rm -rf /tmp/cs /root/.cache/coursier

# --- GitHub CLI ---------------------------------------------------------------
# 認証情報はホストの ~/.config/gh を compose.yaml で bind mount する
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

# --- runn ---------------------------------------------------------------------
# tests/ の E2E シナリオを流す。Go 製の単一バイナリなので展開して置くだけでよい
RUN arch="$(dpkg --print-architecture)" \
    && curl -fsSL "https://github.com/k1LoW/runn/releases/download/v${RUNN_VERSION}/runn_v${RUNN_VERSION}_linux_${arch}.tar.gz" \
        | tar xzf - -C /usr/local/bin runn \
    && chmod 0755 /usr/local/bin/runn

# --- aqua ---------------------------------------------------------------------
# terraform / tflint / ecspresso / aws cli は aqua.yaml で版を固定して aqua 経由で入れる。
# aqua の bin にあるのは aqua 本体を呼ぶリンクなので、aqua 本体も PATH に要る
RUN arch="$(dpkg --print-architecture)" \
    && curl -fsSL "https://github.com/aquaproj/aqua/releases/download/v${AQUA_VERSION}/aqua_linux_${arch}.tar.gz" \
        | tar xzf - -C /usr/local/bin aqua \
    && chmod 0755 /usr/local/bin/aqua

# --- Node.js (LTS) ------------------------------------------------------------
# setup_lts.x は NodeSource 側でその時点の Active LTS 系に解決される
RUN curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# --- dev user -----------------------------------------------------------------
# debian:trixie は uid/gid 1000 のユーザーを持たないので、そのまま作れる
RUN groupadd --gid ${USER_GID} ${USERNAME} \
    && useradd --uid ${USER_UID} --gid ${USER_GID} -m -s /bin/bash ${USERNAME} \
    && echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${USERNAME} \
    && chmod 0440 /etc/sudoers.d/${USERNAME}

USER ${USERNAME}
WORKDIR /app

# --- named volume のマウント先 ------------------------------------------------
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

RUN printf '\n# custom aliases\nalias ccl='\''claude --dangerously-skip-permissions'\''\n' \
        >> /home/${USERNAME}/.bashrc

# --- aqua の PATH と direnv の hook --------------------------------------------
ENV AQUA_ROOT_DIR="/home/${USERNAME}/.local/share/aquaproj-aqua"
ENV PATH="${AQUA_ROOT_DIR}/bin:${PATH}"
RUN printf '\n# direnv\neval "$(direnv hook bash)"\n' >> /home/${USERNAME}/.bashrc

# --- bun ----------------------------------------------------------------------
# 公式インストーラ。~/.bun/bin に入る
RUN curl -fsSL https://bun.sh/install | bash
ENV BUN_INSTALL="/home/${USERNAME}/.bun"
ENV PATH="${BUN_INSTALL}/bin:${PATH}"
