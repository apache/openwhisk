FROM nginx:1.11

# make cli directories
RUN mkdir -p /etc/nginx/cli/go/download/linux/386 && \
    mkdir -p /etc/nginx/cli/go/download/linux/amd64 && \
    mkdir -p /etc/nginx/cli/go/download/mac/386 && \
    mkdir -p /etc/nginx/cli/go/download/mac/amd64 && \
    mkdir -p /etc/nginx/cli/go/download/windows/386 && \
    mkdir -p /etc/nginx/cli/go/download/windows/amd64

# copy the content.json file
COPY content.json /etc/nginx/cli/go/download/content.json

# copy all cli binaries into image
COPY build/linux/386/wsk /etc/nginx/cli/go/download/linux/386/wsk
COPY build/linux/amd64/wsk /etc/nginx/cli/go/download/linux/amd64/wsk
COPY build/mac/386/wsk /etc/nginx/cli/go/download/mac/386/wsk
COPY build/mac/amd64/wsk /etc/nginx/cli/go/download/mac/amd64/wsk
COPY build/windows/386/wsk.exe /etc/nginx/cli/go/download/windows/386/wsk.exe
COPY build/windows/amd64/wsk.exe /etc/nginx/cli/go/download/windows/amd64/wsk.exe

# copy all cli zip files
COPY build/linux/386/OpenWhisk_CLI-linux-32bit.tgz /etc/nginx/cli/go/download/linux/386/OpenWhisk_CLI-linux-32bit.tgz
COPY build/linux/amd64/OpenWhisk_CLI-linux.tgz /etc/nginx/cli/go/download/linux/amd64/OpenWhisk_CLI-linux.tgz
COPY build/mac/386/OpenWhisk_CLI-mac-32bit.zip /etc/nginx/cli/go/download/mac/386/OpenWhisk_CLI-mac-32bit.zip
COPY build/mac/amd64/OpenWhisk_CLI-mac.zip /etc/nginx/cli/go/download/mac/amd64/OpenWhisk_CLI-mac.zip
COPY build/windows/386/OpenWhisk_CLI-windows-32bit.zip /etc/nginx/cli/go/download/windows/386/OpenWhisk_CLI-windows-32bit.zip
COPY build/windows/amd64/OpenWhisk_CLI-windows.zip /etc/nginx/cli/go/download/windows/amd64/OpenWhisk_CLI-windows.zip

# copy the blackbox image
COPY build/blackbox-0.1.0.tar.gz /etc/nginx/blackbox-0.1.0.tar.gz
