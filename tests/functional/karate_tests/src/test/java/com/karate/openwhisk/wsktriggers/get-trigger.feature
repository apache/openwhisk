#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#this feature is for get the details of the a trigger 

@ignore
Feature: Get a Trigger details
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true
	
	Scenario: As a user i want to get the details of a trigegr
		Given url BaseUrl
    And path '/api/v1/namespaces/'+nameSpace+'/triggers/'+triggerName
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    When method get
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval 
    """
    if(responseStatusCode==200){
    	 karate.log("Got the rule details");
    	 karate.set('trigger_details', response)
    	 }
    else if(responseStatusCode == 404){
       karate.log("The requested resource does not exist.");
       }
    """
