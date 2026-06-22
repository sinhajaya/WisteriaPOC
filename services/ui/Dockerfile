# ── Wisteria UI · React + Vite SPA ───────────────────────────
# Multi-stage: build the static bundle with Node, serve it with nginx.
# nginx also reverse-proxies /api, /static, /catalog to wisteria-api
# (replicating vite.config.ts's dev proxy) so the SPA stays same-origin.

# Stage 1 — build
FROM node:20-alpine AS build
WORKDIR /ui
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2 — serve
FROM nginx:alpine
COPY --from=build /ui/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80