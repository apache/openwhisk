# Dockerfile for python action
FROM ubuntu:14.04

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install python
RUN apt-get -y purge
RUN apt-get -y update
RUN apt-get -y install --fix-missing python2.7
RUN apt-get -y install --fix-missing python2.7-dev
RUN apt-get clean

# Install Python CGI helpers.
RUN apt-get -y install --fix-missing python-distribute python-pip
RUN pip install -U flask
RUN pip install -U gevent
 
ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /pythonAction
ADD pythonaction.py /pythonAction/

CMD ["/bin/bash", "-c", "cd pythonAction && python -u pythonaction.py"]
