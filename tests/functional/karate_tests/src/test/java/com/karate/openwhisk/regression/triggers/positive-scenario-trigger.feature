#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.
#Keywords Summary : This feature is all about positive regression test cases of Triggers

@regression
Feature: This feature contains negative regression test cases of openwhisk triggers

	Background: 
    * configure ssl = true
    * def nameSpace = 'guest'
    * def nameSpace2 = 'normaluser'
    * def scriptcode = call read('classpath:com/karate/openwhisk/functions/hello-world.js')
    * def scriptcodeWithParam = call read('classpath:com/karate/openwhisk/functions/greetings.js')
    #* def base64encoding = read('classpath:com/karate/openwhisk/utils/base64.js')
    #get the Auth
    * def getNSCreds = callonce read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def Auth = getNSCreds.Auth
    * print "Got the Creds for the guest user"
    * print Auth

	#@ignore  
	Scenario: As user i want to verify create a trigger with a name that contains spaces
		* print "Test case started --> verify create a trigger with a name that contains spaces"
		# Get User Auth
    #* def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    #* def result = getNSCreds.result
    #* def Auth = base64encoding(result)
    #* print "Got the Creds for the guest user"
    #* print Auth
    #create a trigger
    * def UUID = java.util.UUID.randomUUID()
    * def triggerNameWithSpce = 'Trigger%20'+ UUID
    * def createTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/create-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)', triggerName:'#(triggerNameWithSpce)'}
    * def triggerName = createTrigger.trgrName
    * match createTrigger.responseStatusCode == 200
    * match triggerName == "Trigger "+ UUID
    * print "Successfully Created an trigger"
    
    
    
    