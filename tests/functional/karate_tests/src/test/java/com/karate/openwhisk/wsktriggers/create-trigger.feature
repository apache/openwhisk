#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#this feature is for creating a trigger

@ignore
Feature: Create a Trigger
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true


  Scenario: As a user I want to create a trigger
  	* eval
 		 """
					if (typeof triggerName == 'undefined') {
					    karate.set('triggerName', 'Trigger'+java.util.UUID.randomUUID());
					} else {
							karate.set('triggerName', triggerName);
					}
 		 """
 		* def requestBody = {"name":'#(triggerName)'}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/triggers/'+triggerName+'?overwrite=false'
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method put
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval
    """
    if(responseStatusCode == 200) {
    	 karate.log("Trigger Created");
    	 karate.set('trgrName', response.name )
    	 }
    else if(responseStatusCode == 409){
       karate.log("Duplicate Trigger");
       }
    """
    * print 'Trigger name for the created trigger ' + trgrName
    
    
    
    