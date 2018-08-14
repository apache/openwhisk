#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

# Summary :This feature file will delete the trigger name based on the triggerName

@ignore
Feature:  Delete the trigger on the basis of the trigger name

  Background:
	* configure ssl = true


  Scenario: As a user I want to get the list of triggers available for the given namespace
    * def path = '/api/v1/namespaces/'+nameSpace+'/triggers/'+triggerName
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    When method delete
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval 
    """
    if(responseStatusCode==200){
    	 karate.log("Trigger got deleted");
    	 }
    else if(responseStatusCode == 404){
       karate.log("The requested resource does not exist.");
       }
    """
