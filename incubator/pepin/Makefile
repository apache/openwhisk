REGISTRY_SERVER ?= docker-bladerunner-snapshot.dr-uw2.adobeitc.com
.PHONY: clean
clean:
	rm -rf ./bin/*

.PHONY: pkg
pkg:
	go build ./...

.PHONY: format
format:
	go fmt ./...

.PHONY: router
router: pkg
	go build -o bin/router cmd/router/router.go


.PHONY: echo
echo: pkg
	go build -o bin/echo cmd/echo/echo.go

.PHONY: container
container: clean
	docker build -t ow-knative/router:latest -f router.dockerfile .

.PHONY: deploy-container
deploy-container:
	docker tag ow-knative/router:latest $(REGISTRY_SERVER)/ow-knative/ow-router:0.0.1
	docker tag $(REGISTRY_SERVER)/ow-knative/ow-router:0.0.1 $(REGISTRY_SERVER)/ow-knative/ow-router:latest
	docker push $(REGISTRY_SERVER)/ow-knative/ow-router:0.0.1
	docker push $(REGISTRY_SERVER)/ow-knative/ow-router:latest

.PHONY: deploy-router
deploy-router:
	cd helm/pepin/ && \
       helm dependency update && \
       helm install \
           --set imageCredentials.registry=${REGISTRY_SERVER} \
           --set imageCredentials.username=${K8S_USER} \
           --set imageCredentials.password=${K8S_PASSWORD} \
           .

.PHONY: echo-container
echo-container: clean
	docker build -t ow-knative/ow-echo:latest -f echo.dockerfile .
	docker tag ow-knative/ow-echo:latest $(REGISTRY_SERVER)/ow-knative/ow-echo:0.0.1
