.PHONY: run
run: setup start-docker-compose setup-couchdb

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
	until $$(curl --output /dev/null --silent --head --fail http://127.0.0.1:5984/_all_dbs); do printf '.'; sleep 5; done
	curl -X PUT --data '"false"' http://127.0.0.1:5984/_config/query_server_config/reduce_limit

.PHONY: stop
stop:
	docker-compose stop
	docker-compose rm