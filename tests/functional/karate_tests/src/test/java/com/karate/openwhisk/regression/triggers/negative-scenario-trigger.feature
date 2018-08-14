#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.
#Keywords Summary : This feature is all about negative regression test cases of Triggers
@regression
Feature: This feature contains negative regression test cases of openwhisk triggers

	Background: 
    * configure ssl = true
    * def nameSpace = 'guest'
    * def nameSpace2 = 'normaluser'
    * def scriptcode = call read('classpath:com/karate/openwhisk/functions/hello-world.js')
    * def scriptcodeWithParam = call read('classpath:com/karate/openwhisk/functions/greetings.js')
    #get the Auth
    * def getNSCreds = callonce read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def Auth = getNSCreds.Auth
    * print "Got the Creds for the guest user"
    * print Auth
    
  #@ignore  
  Scenario: As a user i want to verify reject creation of duplicate triggers
    * print "Test case started --> verify reject creation of duplicate triggers"
    #create a trigger
    * def createTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/create-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def triggerName = createTrigger.trgrName
    * match createTrigger.responseStatusCode == 200 
    * print "Successfully Created an trigger"
    
    #Create the same trigger again
    * def createTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/create-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)', triggerName:'#(triggerName)'}
    * def triggerName = createTrigger.trgrName
    * match createTrigger.responseStatusCode == 409 
    * print "Successfully rejected for duplicate trigger"
    * print "Test case completed --> verify reject creation of duplicate triggers"
    
  #@ignore  
	Scenario: As a user i want to verify reject delete of trigger that does not exist
    * print "Test case started --> verify reject delete of trigger that does not exist" 
    * def triggerName = 'Trigger'+java.util.UUID.randomUUID()
    #delete the trigger
    * def deleteTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/delete-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
	 	* match deleteTrigger.responseStatusCode == 404
	 	* print "Test case completed --> verify reject delete of trigger that does not exist"
	 	
	#@ignore 	
	Scenario: As a user i want to verify reject get of trigger that does not exist
    * print "Test case started --> verify reject get of trigger that does not exist" 
    * def triggerName = 'Trigger'+java.util.UUID.randomUUID()
    #get the trigger details
    * def triggerDetails = call read('classpath:com/karate/openwhisk/wsktriggers/get-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
    * match triggerDetails.responseStatusCode == 404
    * print "Test case completed --> verify reject get of trigger that does not exist"
    
  #@ignore
  Scenario: As a user i want to verify reject firing of a trigger that does not exist
    * print "Test case started --> verify reject firing of a trigger that does not exist"  
    * def triggerName = 'Trigger'+java.util.UUID.randomUUID()
    #fire the trigger
    * def fireTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/fire-trigger.feature') {requestBody:'',nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
		* match fireTrigger.responseStatusCode == 404
    * print "Test case completed --> verify reject firing of a trigger that does not exist"
    
  #@ignore  
  Scenario: As a user i want to verify create and fire a trigger with a rule whose action has been deleted
    * print "Test case started --> verify create and fire a trigger with a rule whose action has been deleted" 
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
    # Delete Action
    * def deleteAction = call read('classpath:com/karate/openwhisk/wskactions/delete-action.feature') {actionName:'#(actionName)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * match deleteAction.responseStatusCode == 200
    * print 'successfully deleted the action'
    #fire the trigger
    * def fireTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/fire-trigger.feature') {requestBody:'{"name":"Manoj","place":"Bangalore"}',nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
    * def actID = fireTrigger.activationId
    * match fireTrigger.responseStatusCode == 202
    * print  = "Successfully fired the trigger"
    * def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.feature') {sheepCount:'20'}
    #Get Activation details
    * def getActivationDetails = call read('classpath:com/karate/openwhisk/wskactions/get-activation-details.feature') { activationId: '#(actID)' ,Auth:'#(Auth)'}
    * print "Successfully pulled the activation details"
    * string log = getActivationDetails.activationDetails.logs
    * print 'logs are: ' + log
    * string error = "The requested resource does not exist."
    * match log contains error
    * print "Test case completed --> verify create and fire a trigger with a rule whose action has been deleted"
    
  #@ignore  
  Scenario: As a user i want to verify create and fire a trigger having an active rule and an inactive rule
    * print "Test case started --> verify create and fire a trigger having an active rule and an inactive rule"
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
     #create another rule to make it disable
    * def trgrName = '/'+nameSpace +'/'+triggerName
    * def actName = '/'+nameSpace +'/'+actionName
    * def createRule = call read('classpath:com/karate/openwhisk/wskrules/create-rule.feature') {triggerName:'#(trgrName)', actionName:'#(actName)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * match createRule.responseStatusCode == 200
    * def ruleName = createRule.rulName
    * print 'successfully created the rule'
    #disable the rule
    * def disableRule = call read('classpath:com/karate/openwhisk/wskrules/disable-rule.feature') {ruleName:'#(ruleName)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * match disableRule.responseStatusCode == 200
    #fire the trigger
    * def fireTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/fire-trigger.feature') {requestBody:'{"name":"Manoj","place":"Bangalore"}',nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',triggerName:'#(triggerName)'}
    * def actID = fireTrigger.activationId
    * print  = "Successfully fired the trigger"
    * def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.feature') {sheepCount:'20'}
    #Get Activation details
    * def getActivationDetails = call read('classpath:com/karate/openwhisk/wskactions/get-activation-details.feature') { activationId: '#(actID)' ,Auth:'#(Auth)'}
    * print "Successfully pulled the activation details"
    * string log = getActivationDetails.activationDetails.logs[1]
    * print 'log is: ' + log
    * string error = "Rule \'guest/" + ruleName + "\' is inactive"
    * print "error message is: " + error
    * match log contains error
    * print "Test case completed --> verify create and fire a trigger having an active rule and an inactive rule"
    
    