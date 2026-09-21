# syntax=docker/dockerfile:1
FROM ubuntu:24.04

ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
# sbt ランチャーの版。実際に走る sbt の版は project/build.properties が決める
ARG SBT_VERSION=1.13.0

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# --- base packages + JDK ------------------------------------------------------
# JDK は Ubuntu 公式リポジトリの openjdk-25 (LTS)。Metals は JDK 17 以上を要求する
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg \
        git openssh-client \
        sudo less procps locales vim unzip jq \
        openjdk-25-jdk-headless \
    && sed -i 's/# en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen \
    && locale-gen \
    && rm -rf /var/lib/apt/lists/*

# --- sbt ----------------------------------------------------------------------
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
        | tar xzf - -C /usr/share \
    && chown -R root:root /usr/share/sbt \
    && chmod -R 755 /usr/share/sbt \
    && ln -s /usr/share/sbt/bin/sbt /usr/local/bin/sbt

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

# --- dev user -----------------------------------------------------------------
# ubuntu:24.04 は uid/gid 1000 の `ubuntu` ユーザーを最初から持っているので消して
# 作り直す
RUN userdel -r ubuntu \
    && groupadd --gid ${USER_GID} ${USERNAME} \
    && useradd --uid ${USER_UID} --gid ${USER_GID} -m -s /bin/bash ${USERNAME} \
    && echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${USERNAME} \
    && chmod 0440 /etc/sudoers.d/${USERNAME}

USER ${USERNAME}
WORKDIR /app

# --- named volume のマウント先 ------------------------------------------------
# named volume は初回作成時にイメージ側ディレクトリの所有権を引き継ぐので、
# sbt / ivy / coursier のキャッシュ先と Claude Code の設定先は dev 所有で先に作っておく
RUN mkdir -p \
        /home/${USERNAME}/.sbt \
        /home/${USERNAME}/.ivy2 \
        /home/${USERNAME}/.cache/coursier \
        /home/${USERNAME}/.claude


# ~/.config/gh(bind mount)のマウント先をあらかじめ dev 所有で焼いておく。
# 無いとランタイムが親 ~/.config ごと root で作ってしまう。なお gh dir 自体の
# 所有権はホスト側ディレクトリで決まるので、そちらは devcontainer.json の
# initializeCommand が担保する(bind mount は named volume と違い
# イメージ内の所有権を引き継がない)
RUN mkdir -p /home/${USERNAME}/.config/gh

# --- Claude Code -------------------------------------------------------------------
# 公式の native installer。本体は ~/.local/share/claude/versions/ に置かれ
# ~/.local/bin/claude がそこへの symlink になる。認証・設定 (~/.claude) は
# compose.yaml の named volume 側なので、本体はイメージに焼いても衝突しない。
# ~/.local/bin は非ログインシェル (docker exec / VS Code のターミナル) では
# .profile を通らず PATH に乗らないので ENV で通しておく
RUN curl -fsSL https://claude.ai/install.sh | bash
ENV PATH="/home/${USERNAME}/.local/bin:${PATH}"

RUN printf '\n# custom aliases\nalias ccl='\''claude --dangerously-skip-permissions'\''\n' \
        >> /home/${USERNAME}/.bashrc