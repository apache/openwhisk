.PHONY: docker
docker:
	docker build -t chetanmeh/openwhisk-user-metrics .

.PHONY: docker-run
docker-run:
	docker run  -p 9095:9095 --name user-metrics -e "KAFKA_HOSTS=172.17.0.1:9093" chetanmeh/openwhisk-user-metrics

scala:
	./gradlew  build

.PHONY: all
all: scala docker docker-run


