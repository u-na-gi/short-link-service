# syntax=docker/dockerfile:1
# 開発用。ソースは compose でマウントし、sbt run のホットリロードで動かす。
#
# JDK と sbt の置き場所は .devcontainer/Dockerfile と揃える。sbt は起動時に .bsp/sbt.json へ
# java と sbt-launch.jar の絶対パスを書くので、ずれると devcontainer 側の Metals が起動できなくなる。
FROM debian:trixie

ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
# sbt ランチャーの版。実際に走る sbt の版は project/build.properties が決める
ARG SBT_VERSION=1.13.0

ENV LANG=C.UTF-8

# Play 3.0 のサポート対象は Java LTS 11/17/21 なので 21 に固定する
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl \
        openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
        | tar xzf - -C /usr/share \
    && chown -R root:root /usr/share/sbt \
    && chmod -R 755 /usr/share/sbt \
    && ln -s /usr/share/sbt/bin/sbt /usr/local/bin/sbt

# マウントしたソースに書くファイル (logs/ など) の所有者をホストのユーザーと揃える
RUN groupadd --gid ${USER_GID} ${USERNAME} \
    && useradd --uid ${USER_UID} --gid ${USER_GID} -m -s /bin/bash ${USERNAME}

# 名前付きボリュームは初回にイメージ側のディレクトリの所有者を引き継ぐ。
# 先に作っておかないと root 所有になり、sbt が書き込めない
RUN mkdir -p /home/${USERNAME}/.sbt /home/${USERNAME}/.cache/coursier /app/target /app/project/target \
    && chown -R ${USERNAME}:${USERNAME} /home/${USERNAME} /app

USER ${USERNAME}
WORKDIR /app

EXPOSE 9000

# 開発サーバは 0.0.0.0:9000 で待ち受ける。標準入力が閉じると止まるので compose で tty を付ける
CMD ["sbt", "run"]
