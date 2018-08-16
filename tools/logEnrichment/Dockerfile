# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

from fluent/fluent-bit:0.14.1

COPY etc/* /fluent-bit/etc/

RUN mkdir /input-logs
RUN mkdir /output-logs

# default configuration of Fluent Bit Service settings
ENV OW_FLUENTBIT_LOG_LEVEL=info
ENV OW_FLUENTBIT_FLUSH_SECONDS=1

# sqlite3 database used by tail input plugin to track file positions
ENV OW_FLUENTBIT_TAIL_DB=/fluent-bit/taillog.db

# Default input path for tail input plugin
ENV OW_FLUENTBIT_INPUT_PATH=/input-logs/*

# Default value for file-based output of enriched user logs
ENV OW_FLUENTBIT_OUTPUT_PATH=/output-logs/user-logs-json.log

# Default configuration: docker logs => stdout
# Mainly useful for dev/testing
ENV OW_FLUENTBIT_CONF_FILE=/fluent-bit/etc/ow-dcf-stdout.conf


CMD /fluent-bit/bin/fluent-bit --config=$OW_FLUENTBIT_CONF_FILE
