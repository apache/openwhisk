FROM golang:1.6

ADD . /src/github.com/

# The BUILDTIME argument is used as the CLI build time property (wsk property get --all)
ARG BUILDTIME

# Download and install the godep tool
RUN echo "Installing the godep tool"
ENV GOPATH=/
RUN go get github.com/tools/godep

# Load all of the dependencies from the previously generated/saved godep generated godeps.json file
RUN echo "Restoring Go dependencies"
RUN cd /src/github.com/go-whisk-cli && /bin/godep restore -v

# Generate a Go package dependency list
# NOTE: Currently, the 'go list' command will not work against the current Go CLI non-standard file structure
#RUN cd /src/github.com/go-whisk-cli && go list -f '{{join .Deps "\n"}}' > ../../../wsk.deps.out
RUN cd /src/github.com/go-whisk-cli && echo "Dependencies list requires restructuring the GO CLI packages" > ../../../wsk.deps.out

# Build the Go wsk CLI binaries
ENV GOOS=windows
ENV EXEC=wsk.exe
ENV ARCHS="386 amd64"

RUN for GOARCH in $ARCHS; do cd /src/github.com/go-whisk-cli && go build -ldflags "-X commands.CLI_BUILD_TIME=$BUILDTIME" -v -o $GOOS/$GOARCH/$EXEC main.go; done

ENV GOOS=darwin
ENV ARCHS="386 amd64 arm arm64"
ENV EXEC=wsk

RUN for GOARCH in $ARCHS; do cd /src/github.com/go-whisk-cli && go build -ldflags "-X commands.CLI_BUILD_TIME=$BUILDTIME" -v -o mac/$GOARCH/$EXEC main.go; done

ENV GOOS=linux
ENV ARCHS="386 amd64 arm arm64 ppc64 ppc64le mips64 mips64le"
ENV EXEC=wsk

RUN for GOARCH in $ARCHS; do cd /src/github.com/go-whisk-cli && go build -ldflags "-X commands.CLI_BUILD_TIME=$BUILDTIME" -v -o $GOOS/$GOARCH/$EXEC main.go; done
