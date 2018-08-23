# openwhisk-karate
This directory contains all the Test Cases needed to do various forms of automated test on the OpenWhisk environments.These tests will also complement the existing Scala based Test cases.
These tests are based on the BDD model and enabled quick on-boarding of the tests.
It is based of karate (https://github.com/intuit/karate) framework. 


### How to run functional test
1. Navigate to the root folder
2. Use the following command to run the above selected suite: `gradle clean test --tests com.karate.openwhisk.smoketests.SmokeTestRunner` (This will run all the tests in com.karate.openwhisk.smoketests package.)

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



### more info
1. https://github.com/intuit/karate/tree/master/karate-demo
2. https://github.com/intuit/karate
3. https://gatling.io/docs/2.3/general/simulation_setup/
4. http://toolsqa.com/cucumber/cucumber-jvm-feature-file/

