#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

# Summary :This feature file can be used to create actions

@ignore
Feature: Create an Action
  I want to use this template for my feature file
  
  Background:
* configure ssl = true


  Scenario: As a user I want to create an action
    
 #Create an Action
 		* eval
 		 """
					if (typeof actionName == 'undefined') {
					    karate.set('actionName', 'Testing'+java.util.UUID.randomUUID());
					} else {
							karate.set('actionName', actionName);
					}
 		 """
 		 
 			* eval
 		 """
					if (typeof webAction == 'undefined') {
					    karate.set('webAction', 'false');
					} else {
							karate.set('webAction', 'true');
					}
 		 """
 		 
    * def requestBody = {"namespace":'#(nameSpace)',"name":'#(actionName)',"exec":{"kind":"nodejs:default","code":'#(script)'},"annotations":[{"key":"web-export","value":true},{"key":"raw-http","value":false},{"key":"final","value":true}]}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/actions/'+actionName+'?overwrite=false'
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method put
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval
    """
    if(responseStatusCode == 200) {
    	 karate.log("Action Created");
    	 }
    else if(responseStatusCode == 409){
       karate.log("Duplicate Action");
       }
    """
    #* match responseStatusCode == 200
    * eval if(responseStatusCode == 200) karate.set('actName', response.name )
    * print 'Action name for the created action ' + actName
    
    
    
  