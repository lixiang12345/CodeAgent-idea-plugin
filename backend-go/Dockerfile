# syntax=docker/dockerfile:1
# Multi-stage: build the Go backend in golang:1.26, ship a small alpine runtime.
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/augment-local ./cmd/server

FROM alpine:3.20
RUN apk add --no-cache ca-certificates && adduser -D -u 10001 augment
WORKDIR /app
COPY --from=build /out/augment-local /app/augment-local
USER augment
EXPOSE 8445 8787
ENV OIDC_ADDR=:8445 TENANT_ADDR=:8787
ENTRYPOINT ["/app/augment-local"]
