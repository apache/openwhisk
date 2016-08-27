# Dockerfile for python actions, overrides and extends ActionRunner from actionProxy
FROM dockerskeleton

RUN apk add --no-cache python-dev

ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /pythonAction
ADD pythonrunner.py /pythonAction/

CMD ["/bin/bash", "-c", "cd pythonAction && python -u pythonrunner.py"]
