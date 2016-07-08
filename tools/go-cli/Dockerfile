FROM golang:1.6

# Install zip
RUN apt-get -y update && \
    apt-get -y install zip

# Download and install the godep tool
RUN echo "Installing the godep tool"
ENV GOPATH=/
RUN go get github.com/tools/godep

ADD . /src/github.com/

# Load all of the dependencies from the previously generated/saved godep generated godeps.json file
RUN echo "Restoring Go dependencies"
RUN cd /src/github.com/go-whisk-cli && /bin/godep restore -v

# Generate a Go package dependency list
# NOTE: Currently, the 'go list' command will not work against the current Go CLI non-standard file structure
#RUN cd /src/github.com/go-whisk-cli && go list -f '{{join .Deps "\n"}}' > ../../../wsk.deps.out
RUN cd /src/github.com/go-whisk-cli && echo "Dependencies list requires restructuring the GO CLI packages" > ../../../wsk.deps.out

# All of the Go CLI binaries will be placed under a build folder
RUN mkdir /src/github.com/go-whisk-cli/build

# The BUILDTIME argument is used as the CLI build time property (wsk property get --all)
# Disabled for now to speed build times
#ARG BUILDTIME

# Build the Go wsk CLI binaries
ENV GOOS=windows
ENV EXEC=wsk.exe
ENV ARCHS="386 amd64"
RUN for GOARCH in $ARCHS; do \
      cd /src/github.com/go-whisk-cli && \
      go build -ldflags "-X main.CLI_BUILD_TIME=`date -u '+%Y-%m-%dT%H:%M:%S%:z'`" -v -o build/$GOOS/$GOARCH/$EXEC main.go && \
      zip build/$GOOS/$GOARCH/openwhisk-win-$GOARCH.zip build/$GOOS/$GOARCH/$EXEC; \
    done

ENV GOOS=darwin
ENV ARCHS="386 amd64 arm arm64"
ENV EXEC=wsk
RUN for GOARCH in $ARCHS; do \
      cd /src/github.com/go-whisk-cli && \
      go build -ldflags "-X main.CLI_BUILD_TIME=`date -u '+%Y-%m-%dT%H:%M:%S%:z'`" -v -o build/mac/$GOARCH/$EXEC main.go && \
      tar -cf build/mac/$GOARCH/openwhisk-mac-$GOARCH.tar build/mac/$GOARCH/$EXEC && \
      gzip < build/mac/$GOARCH/openwhisk-mac-$GOARCH.tar > build/mac/$GOARCH/openwhisk-mac-$GOARCH.tar.gz && \
      rm -f build/mac/$GOARCH/openwhisk-mac-$GOARCH.tar; \
    done

ENV GOOS=linux
ENV ARCHS="386 amd64 arm arm64 ppc64 ppc64le mips64 mips64le"
ENV EXEC=wsk
RUN for GOARCH in $ARCHS; do \
      cd /src/github.com/go-whisk-cli && \
      go build -ldflags "-X main.CLI_BUILD_TIME=`date -u '+%Y-%m-%dT%H:%M:%S%:z'`" -v -o build/$GOOS/$GOARCH/$EXEC main.go && \
      tar -cf build/$GOOS/$GOARCH/openwhisk-$GOOS-$GOARCH.tar build/$GOOS/$GOARCH/$EXEC && \
      gzip < build/$GOOS/$GOARCH/openwhisk-$GOOS-$GOARCH.tar > build/$GOOS/$GOARCH/openwhisk-$GOOS-$GOARCH.tar.gz && \
      rm -f build/$GOOS/$GOARCH/openwhisk-$GOOS-$GOARCH.tar; \
    done
