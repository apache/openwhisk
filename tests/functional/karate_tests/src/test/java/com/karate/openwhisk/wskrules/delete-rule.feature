#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

# Summary :This feature file will delete the rules name based on the rule name

@ignore
Feature:  Delete the rule on the basis of the rule name

  Background:
	* configure ssl = true


  Scenario: As a user I want to get the list of rules available for the given namespace
    * def path = '/api/v1/namespaces/'+nameSpace+'/rules/'+ruleName
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    When method delete
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval 
    """
    if(responseStatusCode==200){
    	 karate.log("Rule got deleted");
    	 }
    else if(responseStatusCode == 404){
       karate.log("The requested resource does not exist.");
       }
    """
