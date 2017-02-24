# Dockerfile for python actions, overrides and extends ActionRunner from actionProxy
FROM dockerskeleton

RUN apk add --no-cache \
        bzip2-dev \
        gcc \
        libc-dev \
        libxslt-dev \
        libxml2-dev \
        libffi-dev \
        openssl-dev

# Install common modules for python
RUN pip install \
    beautifulsoup4==4.5.3 \
    httplib2==0.10.3 \
    kafka_python==1.3.2 \
    lxml==3.7.3 \
    python-dateutil==2.6.0 \
    requests==2.13.0 \
    scrapy==1.3.3 \
    simplejson==3.10.0 \
    virtualenv==15.1.0 \
    twisted==17.1.0

ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /pythonAction
ADD pythonrunner.py /pythonAction/

CMD ["/bin/bash", "-c", "cd pythonAction && python -u pythonrunner.py"]
