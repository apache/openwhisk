#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#this feature is about fire a trigger

@ignore
Feature: Fire a Trigger
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true


  Scenario: As a user I want to fire a trigger
  	* string payload = requestBody
    * def requestBody = {}    
    * def path = '/api/v1/namespaces/'+nameSpace+'/triggers/'+triggerName
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method post
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval 
    """
    if(responseStatusCode==202){
    	 karate.log("Trigger got fired");
    	 karate.set('activationId', response.activationId);
    	 }
    else if(responseStatusCode == 404){
       karate.log("The requested resource does not exist.");
       }
    """
    * print 'Activation ID for the fired trigger ' + activationId
