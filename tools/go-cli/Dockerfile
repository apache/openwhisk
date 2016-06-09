FROM golang:1.6

ADD . /src/github.com/

ARG BUILDTIME

ENV GOOS=windows
ENV EXEC=wsk.exe
ENV ARCHS="386 amd64"

RUN for GOARCH in $ARCHS; do cd /src/github.com/go-whisk-cli && go get -d -v && go build -ldflags "-X commands.CLI_BUILD_TIME=$BUILDTIME" -v -o $GOOS/$GOARCH/$EXEC main.go; done

ENV GOOS=darwin
ENV ARCHS="386 amd64 arm arm64"
ENV EXEC=wsk

RUN for GOARCH in $ARCHS; do cd /src/github.com/go-whisk-cli && go get -d -v && go build -ldflags "-X commands.CLI_BUILD_TIME=$BUILDTIME" -v -o mac/$GOARCH/wsk main.go; done

ENV GOOS=linux
ENV ARCHS="386 amd64 arm arm64 ppc64 ppc64le mips64 mips64le"
ENV EXEC=wsk

RUN for GOARCH in $ARCHS; do cd /src/github.com/go-whisk-cli && go get -d -v && go build -ldflags "-X commands.CLI_BUILD_TIME=$BUILDTIME" -v -o $GOOS/$GOARCH/wsk main.go; done
