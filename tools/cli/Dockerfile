FROM golang:1.8.4

# Install zip
RUN apt-get -y update && \
    apt-get -y install zip

ENV GOPATH=/

# Download and install tools
RUN echo "Installing the godep tool"
RUN go get github.com/tools/godep

RUN echo "Installing the go-bindata tool"
RUN go get github.com/jteeuwen/go-bindata/...

# Load all of the dependencies from the previously generated/saved godep generated godeps.json file
ADD ./go-whisk-cli/Godeps /src/github.com/go-whisk-cli/Godeps
RUN echo "Restoring Go dependencies"
RUN cd /src/github.com/go-whisk-cli && /bin/godep restore -v

# Collect all translated strings into single .go module
RUN echo "Generating i18n Go module"
ADD ./go-whisk-cli/wski18n /src/github.com/go-whisk-cli/wski18n
ADD ./go-whisk/wski18n /src/github.com/go-whisk/wski18n
RUN cd /src/github.com/go-whisk-cli && /bin/go-bindata -pkg wski18n -o wski18n/i18n_resources.go wski18n/resources
RUN cd /src/github.com/go-whisk && /bin/go-bindata -pkg wski18n -o wski18n/i18n_resources.go wski18n/resources

# Generate a Go package dependency list
# NOTE: Currently, the 'go list' command will not work against the current Go CLI non-standard file structure
#RUN cd /src/github.com/go-whisk-cli && go list -f '{{join .Deps "\n"}}' > ../../../wsk.deps.out
ADD . /src/github.com
RUN cd /src/github.com/go-whisk-cli && echo "Dependencies list requires restructuring the GO CLI packages" > ../../../wsk.deps.out

# All of the Go CLI binaries will be placed under a build folder
RUN mkdir /src/github.com/go-whisk-cli/build

ARG CLI_OS
ARG CLI_ARCH

# Build the Go wsk CLI binaries and compress resultant binaries
RUN chmod +x /src/github.com/build.sh
RUN cd /src/github.com/ && ./build.sh
