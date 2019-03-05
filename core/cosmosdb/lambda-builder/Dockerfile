# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM scala

ENV UID=1001 \
    NOT_ROOT_USER=owuser


# Copy app jars
ADD build/distributions/lambda-builder.tar /

COPY init.sh /
RUN chmod +x init.sh

RUN adduser -D -u ${UID} -h /home/${NOT_ROOT_USER} -s /bin/bash ${NOT_ROOT_USER}

# It is possible to run as non root if you dont need invoker capabilities out of the controller today
#USER ${NOT_ROOT_USER}

EXPOSE 8080
CMD ["./init.sh", "0"]
