#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

# Summary :This feature file will check for the containers

@ignore
Feature:  Invoke action and return activation ID

  Background:
* configure ssl = true


  Scenario: As a user I want to invoke an action and return the activationID which can be used for other Test Cases
    * string payload = requestBody
    * def requestBody = {}    
    * def path = '/api/v1/namespaces/'+nameSpace+'/actions/'+actionName+params
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method post
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    
    * eval 
    """
    if(responseStatusCode==200){
    	 karate.log("Action got invoked");
    	 karate.set('activationId', response.activationId);
    	 }
    else if(responseStatusCode == 404){
       karate.log("The requested Action does not exist.");
       }
    """
    #And def activationId = response.activationId
    * print 'Activation ID for the Invoke action ' + activationId
    #* def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.js')(1000)