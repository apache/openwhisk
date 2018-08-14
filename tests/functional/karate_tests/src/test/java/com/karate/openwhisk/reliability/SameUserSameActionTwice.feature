#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.s
# Summary :This feature file will check for the containers

@ignore
Feature:  Invoke same action from User 1 so that a warmed container is used on each invocation

  Background:
* configure ssl = true


  Scenario: As a user I want to invoke an action and check that on action execution a new container should spawn up
  
    #Invoke First action
    * def requestBody = {}
    * string payload = requestBody
    * def path = '/api/v1/namespaces/'+NS_botTester0+'/actions/sleep?blocking=false&result=false'
    Given url BaseUrl+path
    And header Authorization = Auth_botTester0
    And header Content-Type = 'application/json'
    And request payload
    When method post
    And def activationId = response.activationId
     * print 'Activation ID for the Invoke action ' + activationId
    * def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.js')(1000)
    
    #Get Activation details
    * def path = '/api/v1/namespaces/_/activations/'+activationId
    Given url BaseUrl+path
    And header Authorization = Auth_botTester0
    And header Content-Type = 'application/json'
    When method get
    Then status 200
    And match response !contains {"annotations": [{"key": "initTime"}]}
 
      #Invoke Second action
    * def requestBody = {}
    * string payload = requestBody
    * def path = '/api/v1/namespaces/'+NS_botTester0+'/actions/sleep?blocking=false&result=false'
    Given url BaseUrl+path
    And header Authorization = Auth_botTester0
    And header Content-Type = 'application/json'
    And request payload
    When method post
    And def activationId = response.activationId
     * print 'Activation ID for the Invoke action ' + activationId
    * def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.js')(1000)
   
      
#Get Activation details
    * def path = '/api/v1/namespaces/_/activations/'+activationId
    Given url BaseUrl+path
    And header Authorization = Auth_botTester0
    And header Content-Type = 'application/json'
    When method get
    Then status 200
    And match response !contains '{"annotations": [{"key": "initTime"}]}'
 
  
  
  