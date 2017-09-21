FROM scala

COPY build/distributions/invoker.tar ./
RUN tar xf invoker.tar && \
rm -f invoker.tar

EXPOSE 8080
