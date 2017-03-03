# Dockerfile for Python 2 actions, similar to the Python 3-based core/pythonAction
FROM python:2.7.12-alpine

# Upgrade and install basic Python dependencies
RUN apk add --no-cache \
        bash \
        bzip2-dev \
        gcc \
        libc-dev \
        libxslt-dev \
        libxml2-dev \
        libffi-dev \
        linux-headers \
        openssl-dev \
        python-dev

# Install common modules for python
RUN pip install --no-cache-dir --upgrade pip setuptools \
 && pip install --no-cache-dir \
        gevent==1.1.2 \
        flask==0.11.1 \
        beautifulsoup4==4.5.1 \
        httplib2==0.9.2 \
        kafka_python==1.3.1 \
        lxml==3.6.4 \
        python-dateutil==2.5.3 \
        requests==2.11.1 \
        scrapy==1.1.2 \
        simplejson==3.8.2 \
        virtualenv==15.1.0 \
        twisted==16.4.0

ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /pythonAction
ADD actionproxy.py /pythonAction/
ADD pythonrunner.py /pythonAction/

CMD ["/bin/bash", "-c", "cd pythonAction && python -u pythonrunner.py"]
