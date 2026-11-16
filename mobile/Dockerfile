# syntax=docker/dockerfile:1
ARG NODE_IMAGE=node:24-bookworm-slim@sha256:dc26ae92c9fb15cf6b1d63e20f0944b2efe0f115b20e00be48fb23fceb061c54
ARG NGINX_IMAGE=nginx:stable-alpine@sha256:583b7f2fce1cdb8da5ad5c39ebd3c33b98b87d493190e548bdee1fa8c619c311

FROM ${NODE_IMAGE} AS build
WORKDIR /app
RUN chown node:node /app
USER node
COPY --chown=node:node package.json package-lock.json ./
RUN --mount=type=secret,id=proxy_ca,uid=1000,required=false \
    if [ -s /run/secrets/proxy_ca ]; then \
        NODE_EXTRA_CA_CERTS=/run/secrets/proxy_ca npm ci --no-audit --no-fund; \
    else npm ci --no-audit --no-fund; fi
COPY --chown=node:node app.json index.ts App.tsx tsconfig.json ./
COPY --chown=node:node src ./src
COPY --chown=node:node assets ./assets
# Public browser origin is compiled into the static bundle, never a secret.
ARG EXPO_PUBLIC_API_URL=http://localhost:18080
RUN --mount=type=secret,id=proxy_ca,uid=1000,required=false \
    if [ -s /run/secrets/proxy_ca ]; then \
        export NODE_EXTRA_CA_CERTS=/run/secrets/proxy_ca; fi; \
    EXPO_PUBLIC_API_URL="$EXPO_PUBLIC_API_URL" EXPO_NO_DOTENV=1 \
    EXPO_NO_TELEMETRY=1 CI=1 \
    npx --no-install expo export --platform web

FROM ${NGINX_IMAGE} AS runtime
COPY --from=docker --chmod=0644 /web.conf /etc/nginx/nginx.conf
COPY --from=build --chown=nginx:nginx /app/dist /usr/share/nginx/html
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -Y off -q -O /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
