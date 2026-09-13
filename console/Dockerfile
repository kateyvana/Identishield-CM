# Console opérateur (Next.js). Build multi-etapes, sortie autonome. Base epinglee
# (ADR-024). La console lit le flux du coeur (CORE_URL) cote serveur ; elle ne se
# connecte a aucune base et ne detient aucun secret.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
