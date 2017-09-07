FROM nginx:1.11

# copy the content.json file
COPY content.json /etc/nginx/cli/go/download/content.json

# copy all cli binaries into image
COPY build/linux /etc/nginx/cli/go/download/linux
COPY build/mac /etc/nginx/cli/go/download/mac
COPY build/windows /etc/nginx/cli/go/download/windows

# copy the blackbox image
COPY build/blackbox-0.1.0.tar.gz /etc/nginx/blackbox-0.1.0.tar.gz
