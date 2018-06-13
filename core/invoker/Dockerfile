# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM openwhisk/invoker

ADD build/distributions/invoker.tar ./

COPY init.sh /
RUN chmod +x init.sh

EXPOSE 8080
CMD ["./init.sh", "0"]
