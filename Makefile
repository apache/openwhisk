.PHONY: run
run: start-docker-compose setup-couchdb


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