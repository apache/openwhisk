#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#this feature is for creating a rule

@ignore
Feature: Create a Rule
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true


  Scenario: As a user I want to create a rule
  	* eval
 		 """
					if (typeof ruleName == 'undefined') {
					    karate.set('ruleName', 'Rule'+java.util.UUID.randomUUID());
					} else {
							karate.set('ruleName', ruleName);
					}
 		 """
 		* def requestBody = {"name":'#(ruleName)',"status":"","trigger":'#(trgrName)',"action":'#(actName)'}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/rules/'+ruleName+'?overwrite=false'
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method put
    * def responseStatusCode = responseStatus
    * def createRuleResponse = response
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval
    """
    if(responseStatusCode == 200) {
    	 karate.log("Rule Created");
    	 karate.set('rulName', response.name )
    	 }
    else if(responseStatusCode == 409){
       karate.log("Duplicate rule");
       }
    """
    * print 'Rule name for the created rule ' + rulName
    
