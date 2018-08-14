#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#this feature is for enabling a rule

@ignore
Feature: Enable a Rule
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true


  Scenario: As a user I want to enable a rule
 		* def requestBody = {"status":"active","trigger":null,"action":null}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/rules/'+ruleName
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method post
    * def responseStatusCode = responseStatus
    * def enableRuleResponse = response
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
