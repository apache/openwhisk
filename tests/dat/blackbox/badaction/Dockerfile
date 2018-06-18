# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

# Dockerfile for example whisk docker action
FROM dockerskeleton

ENV FLASK_PROXY_PORT 8080

ADD runner.py /actionProxy/

CMD ["/bin/bash", "-c", "cd actionProxy && python -u runner.py"]
