OPEN_WHISK_DB_PROTOCOL ?= http
OPEN_WHISK_DB_HOST ?= localhost
OPEN_WHISK_DB_PORT ?= 5984
OPEN_WHISK_DB_PROVIDER ?= CouchDB
OPEN_WHISK_DB_USERNAME ?= whisk_admin
OPEN_WHISK_DB_PASSWORD ?= some_passw0rd
DB_IMMORTAL_DBS ?= subjects
OPEN_WHISK_DB_ACTIONS ?= whisk_actions

# Quick-Start is a simple way to get started with OpenWhisk locally
#   1. at start it builds the project and the docker containers
#   2. then it starts all components using docker-compose
#   3. it runs a sample hello-world function
#   To stop and cleanup the environment use: make destroy
quick-start: docker run quick-start-pause hello-world quick-start-info

.PHONY: quick-start-pause
quick-start-pause:
	echo "waiting for the Whisk invoker to come up ... "
	until $$(curl --output /dev/null --silent --head --fail http://localhost:8081/ping); do printf '.'; sleep 5; done
	sleep 30

.PHONY: quick-start-info
quick-start-info:
	echo "$$(tput setaf 2)To invoke the function again use: $$(tput setaf 4)make hello-world$$(tput sgr0)"
	echo "$$(tput setaf 2)To stop openwhisk use: $$(tput setaf 4)make destroy$$(tput sgr0)"

docker:
	echo "building the docker images ... "
	./gradlew distdocker

.PHONY: run
run: setup start-docker-compose init-couchdb init-couchdb-actions init-whisk-cli

.PHONY: setup
setup:
	mkdir -p ~/tmp/openwhisk/apigateway/ssl
	cp ./ansible/roles/nginx/files/*.pem ~/tmp/openwhisk/apigateway/ssl
	mkdir -p ~/tmp/openwhisk/apigateway/conf
	cp ./tools/apigateway/whisk-docker-compose.conf ~/tmp/openwhisk/apigateway/conf/

.PHONY: start-docker-compose
start-docker-compose:
	docker-compose up 2>&1 > ~/tmp/openwhisk/docker-compose.log &

.PHONY: init-couchdb
init-couchdb:
	echo "waiting for the database to come up ... "
	until $$(curl --output /dev/null --silent --head --fail http://localhost:5984/_all_dbs); do printf '.'; sleep 5; done

	echo "initializing the database ... "
	# the folder ./config/keys is referenced from createImmortalsDBs.sh
	mkdir -p ./config/keys
	cp ./ansible/files/auth.* ./config/keys
	touch ./config/dbSetup.sh
	OPEN_WHISK_DB_PROVIDER=$(OPEN_WHISK_DB_PROVIDER) \
	    OPEN_WHISK_DB_PROTOCOL=$(OPEN_WHISK_DB_PROTOCOL) \
	    OPEN_WHISK_DB_HOST=$(OPEN_WHISK_DB_HOST) OPEN_WHISK_DB_PORT=$(OPEN_WHISK_DB_PORT) \
	    OPEN_WHISK_DB_USERNAME=$(OPEN_WHISK_DB_USERNAME) OPEN_WHISK_DB_PASSWORD=$(OPEN_WHISK_DB_PASSWORD) \
	    DB_IMMORTAL_DBS=$(DB_IMMORTAL_DBS) DB_WHISK_AUTHS=$(DB_IMMORTAL_DBS) \
	    tools/db/createImmortalDBs.sh --dropit
	# cleanup the files referenced by createImmortalDBs.sh
	rm -rf ./config/keys

.PHONY: init-couchdb-actions
init-couchdb-actions:
	echo "initializing CouchDB Views ... "
	echo "" > whisk.properties
	echo db.provider=$(OPEN_WHISK_DB_PROVIDER) >> whisk.properties
	echo db.protocol=$(OPEN_WHISK_DB_PROTOCOL) >> whisk.properties
	echo db.host=$(OPEN_WHISK_DB_HOST) >> whisk.properties
	echo db.port=$(OPEN_WHISK_DB_PORT) >> whisk.properties
	echo db.username=$(OPEN_WHISK_DB_USERNAME) >> whisk.properties
	echo db.password=$(OPEN_WHISK_DB_PASSWORD) >> whisk.properties
	echo db.whisk.actions=$(OPEN_WHISK_DB_ACTIONS) >> whisk.properties
	tools/db/wipeTransientDBs.sh
	rm whisk.properties

.PHONY: init-whisk-cli
init-whisk-cli:
	echo "waiting for the Whisk controller to come up ... "
	until $$(curl --output /dev/null --silent --head --fail http://localhost:8888/ping); do printf '.'; sleep 5; done
	echo "initializing CLI ... "
	./bin/wsk -v property set --namespace guest --auth `cat ansible/files/auth.guest` --apihost localhost:443 -i

.PHONY: stop
stop:
	docker-compose stop

.PHONY: destroy
destroy: stop
	docker-compose rm
	echo "cleaning other openwhisk containers started by the invoker ... "
	docker ps | grep whisk | awk '{print $$1}' | xargs docker stop | xargs docker rm
	echo "cleaning dangling docker volumes ... "
	docker volume ls -qf dangling=true | xargs docker volume rm
	rm -rf ~/tmp/openwhisk

.PHONY: hello-world
hello-world: create-hello-world-function
	echo "invoking the hello-world function ... "

	echo "$$(tput setaf 4)adding the function to whisk ...$$(tput sgr0)"
	./bin/wsk -i action create hello hello.js

	echo "$$(tput setaf 4)invoking the function ...$$(tput sgr0)"
	./bin/wsk -i action invoke hello --blocking --result
	read -r -p "The function has been invoked. Press any key to continue ... "

	echo "$$(tput setaf 1)deleting the function ...$$(tput sgr0)"
	./bin/wsk -i action delete hello
	rm hello.js

.PHONY: create-hello-world-function
create-hello-world-function:
	echo "$$(tput setaf 2)creating the hello.js function ...$$(tput sgr0)"
	echo 'function main(params) {var name = params.name || "World"; return { payload:  "Hello, " + name + "!" }; }' > hello.js

# Using the hello-world function this task executes a performance test using Apache Benchmark
.PHONY: hello-world-perf-test
hello-world-perf-test: create-hello-world-function
	./bin/wsk -i action create hello-perf hello.js

	docker run \
	    --net openwhisk_default \
	    --link controller jordi/ab ab -k -n 2000 -c 20 \
	    -m POST -H "Authorization:Basic MjNiYzQ2YjEtNzFmNi00ZWQ1LThjNTQtODE2YWE0ZjhjNTAyOjEyM3pPM3haQ0xyTU42djJCS0sxZFhZRnBYbFBrY2NPRnFtMTJDZEFzTWdSVTRWck5aOWx5R1ZDR3VNREdJd1A=" \
	            -H "Content-Type:application/json" \
	            http://controller:8888/api/v1/namespaces/guest/actions/hello-perf?blocking=true

	./bin/wsk -i action delete hello-perf
	rm hello.js