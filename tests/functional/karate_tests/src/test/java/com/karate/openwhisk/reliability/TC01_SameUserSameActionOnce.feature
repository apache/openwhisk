#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.
# Summary :This feature file will check for the containers

@reliability



Feature:  Invoke same action from User 1 so that a warmed container is used on each invocation

  Background:
* configure ssl = true
* def testactivations = read('classpath:com/karate/openwhisk/reliability/loop-invokeaction-same-container.js')

  Scenario: TC01-As a user I want to invoke an action and check that the action executed on the warmed up container and no new container spawns up
    # Call the test File to check the above scenario
    * def nameSpace = NS_botTester1
    * def Auth = Auth_botTester1
    * def result = testactivations('classpath:com/karate/openwhisk/wskactions/invoke-action.feature',2,nameSpace ,Auth)
       
    
  
