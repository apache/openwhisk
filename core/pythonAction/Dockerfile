# Dockerfile for python actions, overrides and extends ActionRunner from actionProxy
FROM dockerskeleton

ENV DEBIAN_FRONTEND noninteractive
 
ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /pythonAction
ADD pythonrunner.py /pythonAction/

CMD ["/bin/bash", "-c", "cd pythonAction && python -u pythonrunner.py"]
