.PHONY: run
run: setup start-docker-compose setup-couchdb init-whisk-cli

.PHONY: setup
setup:
	mkdir -p ~/tmp/openwhisk/apigateway/ssl
	cp ./ansible/roles/nginx/files/*.pem ~/tmp/openwhisk/apigateway/ssl
	mkdir -p ~/tmp/openwhisk/apigateway/conf
	cp ./tools/apigateway/whisk-docker-compose.conf ~/tmp/openwhisk/apigateway/conf/

.PHONY: start-docker-compose
start-docker-compose:
	docker-compose up &

.PHONY: setup-couchdb
setup-couchdb:
	echo "waiting for the database to come up ... "
	until $$(curl --output /dev/null --silent --head --fail http://localhost:5984/_all_dbs); do printf '.'; sleep 5; done
	echo "initializing the database ... "
	curl -X PUT http://localhost:5984/_config/query_server_config/reduce_limit -u whisk_admin:some_passw0rd --data '"false"'
	curl -X PUT http://localhost:5984/subjects -u whisk_admin:some_passw0rd
	curl -X PUT http://localhost:5984/whisk_actions -u whisk_admin:some_passw0rd
	curl -X POST http://localhost:5984/subjects -u whisk_admin:some_passw0rd --data @./ansible/files/auth_index.json -H "Content-type:application/json"
	cat ./ansible/files/auth.guest | awk -F ":" '{ print "{\"_id\":\"guest\", \"subject\":\"guest\", \"uuid\":\"" $$1 "\",\"key\":\""  $$2 "\"}" }' > ~/tmp/openwhisk/guest.json
	curl -X POST http://localhost:5984/subjects -u whisk_admin:some_passw0rd --data @$${HOME}/tmp/openwhisk/guest.json -H "Content-type:application/json"
	cat ./ansible/files/auth.whisk.system | awk -F ":" '{ print "{\"_id\":\"whisk.system\", \"subject\":\"whisk.system\", \"uuid\":\"" $$1 "\",\"key\":\""  $$2 "\"}" }' > ~/tmp/openwhisk/system.json
	curl -X POST http://localhost:5984/subjects -u whisk_admin:some_passw0rd --data @$${HOME}/tmp/openwhisk/system.json -H "Content-type:application/json"

.PHONY: init-whisk-cli
init-whisk-cli:
	echo "waiting for the Whisk controller to come up ... "
	until $$(curl --output /dev/null --silent --head --fail http://localhost:8080/ping); do printf '.'; sleep 5; done
	echo "initializing CLI ... "
	./bin/go-cli/wsk -v property set --namespace guest --auth `cat ansible/files/auth.guest` --apihost localhost:443 -i

.PHONY: stop
stop:
	docker-compose stop

.PHONY: destroy
destroy:
	docker-compose rm
	rm -rf ~/tmp/openwhisk