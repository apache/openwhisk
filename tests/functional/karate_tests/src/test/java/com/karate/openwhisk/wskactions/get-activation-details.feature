#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

# Summary :This feature file can be used to get action destils using action name
@ignore
Feature: Get Action

Background:
* configure ssl = true
 
  Scenario: Get Activation Details
     
#Get Activation details
    * def path = '/api/v1/namespaces/_/activations/'+activationId
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    When method get
    Then status 200
    And def activationDetails = response
    * print 'Activation ID for the Invoke action(I was here in activation details ' + activationId
   # And match response !contains {"annotations": [{"key": "initTime"}]}
   * def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.js')(1000)