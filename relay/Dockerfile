# Pairline's relay for "Your own server" (D-106). A static binary in an empty image: nothing else to update or trust.
#
#   docker build -t pairline-relay .
#   docker run -d --name pairline-relay --restart unless-stopped -p 443:443 -v pairline-relay:/data \
#     -e PAIRLINE_RELAY_PUBLIC=203.0.113.5 pairline-relay
#   docker logs pairline-relay        # the link and QR code
#
# Bump the Go version with relay.yml's. The build stage runs on the builder's own platform and cross-compiles, so a
# multi-architecture image needs no emulation.
FROM --platform=$BUILDPLATFORM golang:1.27-alpine AS build
ARG VERSION=dev
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY go.mod ./
COPY *.go ./
# Reproducible: no cgo, no paths, no build id.
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -trimpath -buildvcs=false -ldflags "-s -w -buildid= -X main.version=${VERSION}" -o /out/pairline-relay . \
 && mkdir -p /out/data

FROM scratch
COPY --from=build /out/pairline-relay /pairline-relay
# A named volume mounted here starts out owned by the relay's user (65532), so it can write its key there.
COPY --from=build --chown=65532:65532 /out/data /data
ENV PAIRLINE_RELAY_DATA=/data \
    PAIRLINE_RELAY_LISTEN=:443
# Not root. Docker lets a container's own users listen on 443 inside its network; with --network host, set
# PAIRLINE_RELAY_LISTEN to a port above 1023.
USER 65532:65532
EXPOSE 443
VOLUME /data
ENTRYPOINT ["/pairline-relay"]
