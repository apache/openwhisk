# golang:1.12-alpine3.10
FROM golang@sha256:87e527712342efdb8ec5ddf2d57e87de7bd4d2fedf9f6f3547ee5768bb3c43ff as builder

RUN apk update && apk add --no-cache git

RUN adduser -D -g '' ow-router
WORKDIR /opt/source/
COPY go.mod .
COPY go.sum .

RUN go mod download
RUN go mod verify
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-w -s" -a -installsuffix cgo -o /go/bin/ow-router ./cmd/router/router.go

FROM scratch
COPY --from=builder /etc/passwd /etc/passwd
COPY --from=builder /go/bin/ow-router /go/bin/ow-router
# Use an unprivileged user.
USER  ow-router
# Run the hello binary.
ENTRYPOINT ["/go/bin/ow-router"]