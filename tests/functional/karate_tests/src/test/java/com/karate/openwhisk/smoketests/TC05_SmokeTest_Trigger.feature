#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#Keywords Summary : This feature is all about smoke test cases of Triggers

@smoketests
Feature: This feature contains smoke test cases of openwhisk triggers

	Background: 
    * configure ssl = true
    * def nameSpace = 'guest'
    * def nameSpace2 = 'normaluser'
    * def scriptcode = call read('classpath:com/karate/openwhisk/functions/hello-world.js')
    * def scriptcodeWithParam = call read('classpath:com/karate/openwhisk/functions/greetings.js')
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def Auth = getNSCreds.Auth
    * print "Got the Creds for the guest user"
    * print Auth

Scenario: As a user i want to verify create, update, get, fire, list and delete trigger
		* print "Test case started --> verify create, update, get, fire, list and delete trigger"
    #create a trigger
    * def createTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/create-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def triggerName = createTrigger.trgrName
    * match createTrigger.responseStatusCode == 200
    * print "Successfully Created an trigger"
    
    #fire a trigger
    * def fireTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/fire-trigger.feature') {requestBody:'',nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
    * def actID = fireTrigger.activationId
    * match fireTrigger.responseStatusCode == 204
    * print "Successfully fired the trigger"
    * def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.feature') {sheepCount:'20'}
    
   	#get trigger details
   	* def triggerDetails = call read('classpath:com/karate/openwhisk/wsktriggers/get-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
    * print "Successfully got the trigger details"
    
    #update the trigger
    * def updateTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/update-trigger.feature') {triggerName:'#(triggerName)',nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def triggerName = updateTrigger.trgrName
    * print triggerName
    * print "Successfully updated the trigger"
    
    # List Triggers
    * def listRules = call read('classpath:com/karate/openwhisk/wsktriggers/list-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * print "Successfully pulled up the list of triggers"
    
    #Delete the trigger
    * def deleteTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/delete-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
  	* match deleteTrigger.responseStatusCode == 200
  	*  print "Trigger got deleted"
  	* print "Test case completed --> verify create, update, get, fire, list and delete trigger"
  	
  #@ignore  
  Scenario: As a user i want to verify create and fire a trigger with a rule
    * print "Test case started --> verify create and fire a trigger with a rule" 
    #create a trigger
    * def createTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/create-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def triggerName = createTrigger.trgrName
    * match createTrigger.responseStatusCode == 200
    * print "Successfully Created an trigger" 
    #create a action
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def actionName = createAction.actName
    * match createAction.responseStatusCode == 200
    * print "Successfully Created an action"
    #create a rule
    * def trgrName = '/'+nameSpace +'/'+triggerName
    * def actName = '/'+nameSpace +'/'+actionName
    * def createRule = call read('classpath:com/karate/openwhisk/wskrules/create-rule.feature') {triggerName:'#(trgrName)', actionName:'#(actName)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * match createRule.responseStatusCode == 200
    * print 'successfully created the rule'
    #fire the trigger
    * def fireTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/fire-trigger.feature') {requestBody:'{"name":"Manoj","place":"Bangalore"}',nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
    * def actID = fireTrigger.activationId
    * print  = "Successfully fired the trigger"
    #Delete the trigger
    * def deleteTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/delete-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
  	* match deleteTrigger.responseStatusCode == 200
  	*  print "Trigger got deleted"
  	* print 'Test Case completed--> verify create and fire a trigger with a rule'
    
