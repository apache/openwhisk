# Dockerfile for python actions, overrides and extends ActionRunner from actionProxy
FROM dockerskeleton

RUN apk add --no-cache \
        bzip2-dev \
        gcc \
        libc-dev \
        libxslt-dev \
        libxml2-dev \
        libffi-dev \
        openssl-dev \
        python-dev

# Install common modules for python
RUN pip install \
    beautifulsoup4 \
    httplib2 \
    lxml \
    python-dateutil \
    requests \
    scrapy \
    simplejson \
    twisted

ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /pythonAction
ADD pythonrunner.py /pythonAction/

CMD ["/bin/bash", "-c", "cd pythonAction && python -u pythonrunner.py"]
