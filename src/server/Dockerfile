# syntax=docker/dockerfile:1
# For development. Sources are mounted by compose and run with sbt run hot reload.
#
# Keep the JDK and sbt locations the same as .devcontainer/Dockerfile. On startup sbt writes the absolute paths of
# java and sbt-launch.jar to .bsp/sbt.json, so if they differ, Metals in the devcontainer cannot start.
FROM debian:trixie

ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
# sbt launcher version. project/build.properties decides the sbt version that actually runs
ARG SBT_VERSION=1.13.0

ENV LANG=C.UTF-8

# Play 3.0 supports Java LTS 11/17/21, so pin to 21
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl \
        openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
        | tar xzf - -C /usr/share \
    && chown -R root:root /usr/share/sbt \
    && chmod -R 755 /usr/share/sbt \
    && ln -s /usr/share/sbt/bin/sbt /usr/local/bin/sbt

# Match the owner of files written into the mounted sources (logs/ etc.) to the host user
RUN groupadd --gid ${USER_GID} ${USERNAME} \
    && useradd --uid ${USER_UID} --gid ${USER_GID} -m -s /bin/bash ${USERNAME}

# A named volume takes the owner of the image's directory on first use.
# If the directory is not created first, it is owned by root and sbt cannot write to it
RUN mkdir -p /home/${USERNAME}/.sbt /home/${USERNAME}/.cache/coursier /app/target /app/project/target \
    && chown -R ${USERNAME}:${USERNAME} /home/${USERNAME} /app

USER ${USERNAME}
WORKDIR /app

EXPOSE 9000

# The dev server listens on 0.0.0.0:9000. It stops when stdin closes, so compose attaches a tty
CMD ["sbt", "run"]
