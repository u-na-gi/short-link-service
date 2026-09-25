# syntax=docker/dockerfile:1
# For development. The source is mounted by compose and runs with Vite HMR.
# The bun version matches the devcontainer (official installer)
FROM oven/bun:1.4.2

# The bun user in the official image is uid 1000, so it matches the owner of the mounted source as is
USER bun
WORKDIR /app

# node_modules is baked into the image and overlaid as an anonymous volume by compose.
# This keeps it from mixing with the host's node_modules (installed in the devcontainer)
COPY --chown=bun:bun package.json bun.lock ./
RUN bun install --frozen-lockfile

EXPOSE 5173

CMD ["bun", "run", "dev"]
