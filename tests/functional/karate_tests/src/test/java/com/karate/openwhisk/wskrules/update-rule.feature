#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#this feature is for updating a rule

@ignore
Feature: Update a Rule
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true


  Scenario: As a user I want to update a rule
 		* def requestBody = {"name":'#(ruleName)',"status":"","trigger":'#(trgrName)',"action":'#(actName)'}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/rules/'+ruleName+'?overwrite=true'
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method put
    * def responseStatusCode = responseStatus
    * def updateRuleResponse = response
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval
    """
    if(responseStatusCode == 200) {
    	 karate.log("Rule updated");
    	 karate.set('rulName', response.name )
    	 }
    else if(responseStatusCode == 409){
       karate.log("Duplicate rule");
       }
    """
    * print 'Rule name for the updated rule ' + rulName
    
