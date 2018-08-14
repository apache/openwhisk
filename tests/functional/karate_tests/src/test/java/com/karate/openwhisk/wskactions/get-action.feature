#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

# Summary :This feature file can be used to get action destils using action name
@ignore
Feature: Get Action

Background:
* configure ssl = true
 
  Scenario: Get Details of an action
    Given url BaseUrl
    And path '/api/v1/namespaces/'+nameSpace+'/actions/'+actionName
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    When method get
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval 
    """
    if(responseStatusCode==200){
    	 karate.log("Got the action details");
    	 karate.set('action_details', response)
    	 }
    else if(responseStatusCode == 404){
       karate.log("The requested Action does not exist");
       }
    """
    #Then status 200
    #And string action_details = response
