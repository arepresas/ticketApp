# syntax=docker/dockerfile:1.7

# -----------------------------------------------------------------------------
# Stage 1: install dependencies (cached separately for faster rebuilds)
# -----------------------------------------------------------------------------
FROM node:24-alpine AS deps
WORKDIR /workspace
# Enable corepack so pnpm is available without a manual install.
RUN corepack enable
COPY package.json pnpm-lock.yaml .npmrc pnpm-workspace.yaml ./

# CI=true makes pnpm non-interactive (no TTY prompts during supply-chain
# verification). The 24h minimum-release-age policy lives in
# pnpm-workspace.yaml and applies to every install.
ENV CI=true
RUN pnpm install --frozen-lockfile --ignore-scripts

# -----------------------------------------------------------------------------
# Stage 2: build the static bundle
# -----------------------------------------------------------------------------
FROM deps AS build
WORKDIR /workspace
COPY . .
RUN pnpm build

# -----------------------------------------------------------------------------
# Stage 3: runtime — nginx-alpine serving the static bundle
# -----------------------------------------------------------------------------
# Distroless does not ship a maintained nginx image (distroless/nginx is empty
# and the static image does not include a web server). nginx:alpine is the
# smallest maintained option (~40 MB) and is the closest to a distroless-style
# minimal runtime: read-only rootfs, no busybox extras.
FROM nginx:1.29-alpine AS runtime

# Drop default config, replace with one tuned for SPA-style static hosting
# of a single ESM bundle.
RUN rm /etc/nginx/conf.d/default.conf
COPY <<'EOF' /etc/nginx/conf.d/default.conf
server {
    listen 8080 default_server;
    listen [::]:8080 default_server;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # Cache static assets aggressively; the bundle is content-hashed.
    location ~* \.(?:css|js|map|wasm)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    # Serve the ESM bundle with the right Content-Type so the browser loads
    # it as a module instead of downloading it.
    location = /ticketapp-front.js {
        types { application/javascript js; }
        add_header Cache-Control "public, max-age=3600";
    }

    # Demo HTML that loads the bundle as a web component.
    location = / {
        try_files /index.html =404;
    }

    # Health endpoint for orchestrators
    location = /healthz {
        access_log off;
        return 200 "ok\n";
    }
}
EOF

# Copy built artifacts
COPY --from=build /workspace/dist /usr/share/nginx/html/
# Replace the demo HTML with one that loads the production bundle
RUN printf '%s\n' \
    '<!DOCTYPE html>' \
    '<html lang="en">' \
    '  <head>' \
    '    <meta charset="UTF-8" />' \
    '    <meta name="viewport" content="width=device-width, initial-scale=1.0" />' \
    '    <title>ticketApp</title>' \
    '  </head>' \
    '  <body>' \
    '    <h1>ticketApp</h1>' \
    '    <ticket-app api-url="/api/tickets"></ticket-app>' \
    '    <script type="module" src="/ticketapp-front.js"></script>' \
    '  </body>' \
    '</html>' \
    > /usr/share/nginx/html/index.html

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -qO- http://localhost:8080/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
