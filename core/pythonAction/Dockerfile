# Dockerfile for python action
FROM ubuntu:14.04

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install Python and helpers
RUN apt-get -y purge && \
    apt-get -y update && \
    apt-get -y install --fix-missing python2.7 && \
    apt-get -y install --fix-missing python2.7-dev && \
    apt-get -y install --fix-missing python-distribute && \
    apt-get -y install --fix-missing python-pip && \
    apt-get clean && \
    pip install -U flask && \
    pip install -U gevent

ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /pythonAction
ADD pythonaction.py /pythonAction/

CMD ["/bin/bash", "-c", "cd pythonAction && python -u pythonaction.py"]
