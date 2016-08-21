# Dockerfile for example whisk docker action
FROM openwhisk/dockerskeleton

ENV DEBIAN_FRONTEND noninteractive
 
ENV FLASK_PROXY_PORT 8080

ADD example.c /action/example.c
RUN cd /action; gcc -o exec example.c

CMD ["/bin/bash", "-c", "cd actionProxy && python -u actionproxy.py"]
