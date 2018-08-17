 #// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.
#Summary :This feature file will check creation ,invocation and deletion of an action
@smoketests


Feature: This feature file will test all the wsk functions

  Background: 
    * configure ssl = true
    * def nameSpace = 'guest'
    * def params = '?blocking=true&result=false'
    * def scriptcode = call read('classpath:com/karate/openwhisk/functions/hello-world.js')
    * def base64encoding = read('classpath:com/karate/openwhisk/utils/base64.js')

  Scenario: TC01-As a user I want to verify and validate create, invoke and delete action
    # Get User Auth
    * print "Test case started-->verify and validate create, invoke and delete action"
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    * def actionName = 'TestAction'+java.util.UUID.randomUUID()
    
    # Create an Action .Create an action for the above defined guest name and assert for successful creation and duplicacy
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
    * eval if(responseStatusCode == 200) karate.set('actName', response.name )
    * print 'Action name for the created action ' + actName
    
    #Invoke a Blocking Action and asset the activationID
    * def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.feature') {sheepCount:'5'}
    * string payload = requestBody
    * def requestBody = {}    
    * def path = '/api/v1/namespaces/'+nameSpace+'/actions/'+actionName+params
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method post
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    
    * eval 
    """
    if(responseStatusCode==200){
       karate.log("Action got invoked");
       karate.set('activationId', response.activationId);
       }
    else if(responseStatusCode == 404){
       karate.log("The requested Action does not exist.");
       }
    """
    * print 'Activation ID for the Invoke action ' + activationId
     
    # Delete Action
    * def path = '/api/v1/namespaces/'+nameSpace+'/actions/'+actionName
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    When method delete
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval 
    """
    if(responseStatusCode==200){
       karate.log("Action got deleted");
       }
    else if(responseStatusCode == 404){
       karate.log("The requested Action does not exist");
       }
    """
    * print "Test case ended-->verify and validate create, invoke and delete action"