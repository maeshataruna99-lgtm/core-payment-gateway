# Builds the frontend and serves it with nginx, which also forwards /api to the backend.
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Demo values only. They are built into the JavaScript, so never pass a real credential.
ARG VITE_DEMO_MERCHANT_KEY=
ARG VITE_DEMO_OPERATOR_KEY=
ENV VITE_DEMO_MERCHANT_KEY=$VITE_DEMO_MERCHANT_KEY
ENV VITE_DEMO_OPERATOR_KEY=$VITE_DEMO_OPERATOR_KEY
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
