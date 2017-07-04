FROM s390x/alpine:3.6
ENTRYPOINT ["/bin/registrator"]
#LABEL registrator

COPY . /go/src/github.com/gliderlabs/registrator
RUN apk add --no-cache --virtual build-deps go git mercurial musl-dev \
#  && apk add --no-cache --virtual build-dependences \
	&& cd /go/src/github.com/gliderlabs/registrator \
	&& export GOPATH=/go \
	&& go get \
	&& go build -ldflags "-X main.Version=$(cat VERSION)" -o /bin/registrator \
	&& rm -rf /go \
	&& apk del build-deps
