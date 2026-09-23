# syntax=docker/dockerfile:1
# 開発用。ソースは compose でマウントし、Vite の HMR で動かす。
# bun の版は devcontainer (公式インストーラ) に揃える
FROM oven/bun:1.4.2

# 公式イメージの bun ユーザーは uid 1000 なので、マウントしたソースの所有者とそのまま揃う
USER bun
WORKDIR /app

# node_modules はイメージに焼き、compose で匿名ボリュームとして被せる。
# ホスト側の node_modules (devcontainer で入れたもの) と混ざらないようにするため
COPY --chown=bun:bun package.json bun.lock ./
RUN bun install --frozen-lockfile

EXPOSE 5173

CMD ["bun", "run", "dev"]
