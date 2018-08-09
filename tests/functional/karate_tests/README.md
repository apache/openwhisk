# openwhisk-karate
This repository contains all the Test Cases needed to do various forms of automated test on the OW environments.These tests will also complement the existing Scala based Test cases.
These tests are based on the BDD model and enabled quick on-boarding of the tests.
It is based of karate (https://github.com/intuit/karate) framework. 

### Structure
The project structure is divided into the following packages:
1. Functions:This package will contain all the test functions needed to Create/Update an action
2. Utils:This package bundles all the utility functions like sleep,generate test string/number together
3. WSKActions:This package has all the OW actions which a User can perform through a CLI.These are re-usable features and are only run through test packages.
4. WSKAdmin:This package has all the OW actions which an Admin can perform through a CLI.These are re-usable features and are only run through test packages.
5. Test Packages(Relibility.tests/resiliency etc):These packages will have all the feature files needed to perform the type of testing defined in the package


### How to run the functional test
1. Clone/Download the repo
2. Pick up the suite to run.For example if you want to run the smoke test navigate root path and run the below given command.
2. Use the following command to run the above selected suite: `mvn test -Dadminauth=<base64-enconded-admin-auth> -Dtest=com.karate.openwhisk.smoketests.SmokeTestRunner -Dadminbaseurl=<Admin/CouchDB URL> -Dbaseurl=<Base Url of Controller>` (This will run all the tests in com.karate.openwhisk.smoketests package.
Example : `mvn test -Dadminauth='d2hpc2tfYWRtaW5PcHM=' -Dtest=com.karate.openwhisk.smoketests.SmokeTestRunner -Dadminbaseurl=https://whisk-couchdb.test.com:443 -Dbaseurl=https://controller-test.com`


### How to run the performance test
1. Navigate to the root path.Check out the performance test package (/ow-karate/src/test/java/com/karate/openwhisk/performance)
2. val createActionTest = scenario("create").exec(karateFeature("classpath:com/karate/openwhisk/performance/load-test-create-action.feature"))


  setUp(createActionTest.inject(rampUsers(5) over (5 seconds))
    ).maxDuration(1 minutes).assertions(global.responseTime.mean.lt(1100))

Tweak the above values as per your test needs/load requirements
3. Use the following command to run the performance: 
`mvn clean install gatling:test -Dgatling.simulationClass=com.karate.openwhisk.performance.LoadTestOnCreateAction -Dadminauth=<base64-enconded-admin-auth> -Dadminbaseurl=<Admin/CouchDB URL> -Dbaseurl=<Base Url of Controller>`
Example : `mvn clean install gatling:test -Dgatling.simulationClass=com.karate.openwhisk.performance.LoadTestOnCreateAction -Dadminauth='d2hpc2tfYWRtaW46YmxhZGVydW5PcHM=' -Dadminbaseurl='https://whisk-couchdb.test.com:443' -Dbaseurl='https://controller-test.com'`


### How to add more tests

1. Select a package(Type of test).Example Reliablity test
2. Add a new feature file which has your test with the following tags `@rlt`

### How to add a new test type
1. Create a package in `src/test/java`.Say for example `com.karate.openwhisk.sanity.tests`
2. Create a feature and runner file 


### Pre-requisites to run the tests
The variables in karate.config

* env-->Environment Name (Optional)
* adminauth-->Admin Auth,Used for Admin API's
* baseurl-->Target URL(SUT)
* adminbaseurl-->Database Url
* NS_botTester[i]=Namespace of the bot testers
* Auth_botTester[i]=Auth of the bot testers(Base64 decode of the credentials)
* MarathonAPIURL=URL of the environment where marathon is hosted.
* Marathon Auth Token=Auth token to use the marathon API's.Links on how to optain and use Marathon API's
1. https://docs.mesosphere.com/1.10/security/ent/iam-api/#/obtaining-an-authentication-token
2. http://mesosphere.github.io/marathon/api-console/index.html



### more info
1. https://github.com/intuit/karate/tree/master/karate-demo
2. https://github.com/intuit/karate
3. https://gatling.io/docs/2.3/general/simulation_setup/

