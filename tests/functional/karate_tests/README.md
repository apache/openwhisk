# openwhisk-karate
This repository contains all the Test Cases needed to do various forms of automated test on the OW environments.These tests will also complement the existing Scala based Test cases.
These tests are based on the BDD model and enabled quick on-boarding of the tests.
It is based of karate (https://github.com/intuit/karate) framework. 


### How to run functional test
1. Clone/Download the repo
2. Navigate to the root folder
2. Use the following command to run the above selected suite: `mvn test -Dtest="com.karate.openwhisk.smoketests.SmokeTestRunner"` (This will run all the tests in com.karate.openwhisk.smoketests package.)

### How to run the performance test 
1. Navigate to the root path.Check out the performance test package (/ow-karate/src/test/java/com/karate/openwhisk/performance)
2. setUp(createActionTest.inject(rampUsers(5) over (5 seconds))
    ).maxDuration(1 minutes).assertions(global.responseTime.mean.lt(1100))
Tweak the above values as per your test needs/load requirements
3. Use the following command to run the performance: 
`mvn clean install gatling:test -Dgatling.simulationClass=com.karate.openwhisk.performance.LoadTest`

### How to add more tests

1. Select a package(Type of test).Example Smoke test
2. Add a new feature file which has your test with the following tags `@smoke`

### How to add a new test type
1. Create a package in `src/test/java`.
2. We can create test types like regression, sanity etc.For example `com.karate.openwhisk.sanitytests`
3. Create a feature and runner file inside the above package 


### Config Variable(Optional) to run the tests
The variables in karate.config

* env-->Environment Name (Optional)
* adminauth-->Admin Auth,Used for Admin API's
* baseurl-->Target URL(SUT)
* adminbaseurl-->Database Url
* MarathonAPIURL=URL of the environment where marathon is hosted.
* Marathon Auth Token=Auth token to use the marathon API's.Links on how to optain and use Marathon API's
1. https://docs.mesosphere.com/1.10/security/ent/iam-api/#/obtaining-an-authentication-token
2. http://mesosphere.github.io/marathon/api-console/index.html



### more info
1. https://github.com/intuit/karate/tree/master/karate-demo
2. https://github.com/intuit/karate
3. https://gatling.io/docs/2.3/general/simulation_setup/

