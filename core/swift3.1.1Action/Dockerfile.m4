# Dockerfile for swift actions, overrides and extends ActionRunner from actionProxy
# This Dockerfile is partially based on: https://github.com/IBM-Swift/swift-ubuntu-docker/blob/master/swift-development/Dockerfile
FROM m4_ifdef(`S390X',`s390x/ubuntu:xenial',`ibmcom/swift-ubuntu:3.1.1')
m4_changequote({{,}})

# Set WORKDIR
WORKDIR /

# Upgrade and install basic Python dependencies
RUN apt-get -y update \
 && apt-get -y install --fix-missing python2.7 python-gevent python-flask zip \
m4_ifdef({{S390X}},{{ #  vvv Continuation of the apt-get statement vvv \
                      clang curl git libicu55 libcurl3 libxml2 libbsd0 \
 && curl -sSL https://s3.amazonaws.com/s390x-openwhisk/swift-3.1.1-RELEASE.tar.gz | tar zfxv - \
}},{{}})
 && apt-get clean && rm -rf /var/lib/apt/lists/*

# COPY the action proxy
RUN mkdir -p /actionProxy /swift3Action
COPY actionproxy.py /actionProxy
COPY epilogue.swift /swift3Action
COPY buildandrecord.py /swift3Action
COPY swift3runner.py /swift3Action
COPY spm-build /swift3Action/spm-build

# Build kitura net
RUN touch /swift3Action/spm-build/main.swift \
  && git config --global advice.detachedHead false \
  && python /swift3Action/buildandrecord.py \
  && rm /swift3Action/spm-build/.build/release/Action

#RUN cd /swift3Action/spm-build; swift build -v -c release; rm /swift3Action/spm-build/.build/release/Action

ENV FLASK_PROXY_PORT 8080

CMD ["/bin/bash", "-c", "cd /swift3Action && PYTHONIOENCODING='utf-8' python -u swift3runner.py"]
