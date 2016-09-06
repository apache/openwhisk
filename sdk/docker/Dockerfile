# Dockerfile for example whisk docker action
FROM dockerskeleton
 
ENV FLASK_PROXY_PORT 8080

### Add source file(s)
ADD example.c /action/example.c

RUN apk add --no-cache --virtual .build-deps \
        bzip2-dev \
        gcc \
        libc-dev \
### Compile source file(s)
 && cd /action; gcc -o exec example.c \
 && apk del .build-deps

CMD ["/bin/bash", "-c", "cd actionProxy && python -u actionproxy.py"]
