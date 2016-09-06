# Dockerfile for example whisk docker action
FROM dockerskeleton
 
ENV FLASK_PROXY_PORT 8080

ADD runner.py /actionProxy/

CMD ["/bin/bash", "-c", "cd actionProxy && python -u runner.py"]
