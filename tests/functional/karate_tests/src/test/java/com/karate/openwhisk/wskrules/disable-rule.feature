#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#this feature is for disabling a rule

@ignore
Feature: Disable a Rule
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true


  Scenario: As a user I want to disable a rule
 		* def requestBody = {"status":"inactive","trigger":null,"action":null}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/rules/'+ruleName
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method post
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval
    """
    if(responseStatusCode == 200) {
    	 karate.log("Rule disbled");
    	 }
    else if(responseStatusCode == 404){
       karate.log("The requested resource does not exist.");
       }	 
    """
