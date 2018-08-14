#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.
#Keywords Summary : This feature is all about basic test cases of openwhisk
@basictests
Feature: This feature contains basic test cases of openwhisks

  Background: 
    * configure ssl = true
    * def nameSpace = 'guest'
    * def nameSpace2 = 'normaluser'
    * def params = '?blocking=true&result=false'
    * def scriptcode = call read('classpath:com/karate/openwhisk/functions/hello-world.js')
    * def base64encoding = read('classpath:com/karate/openwhisk/utils/base64.js')
    * def scriptcodeWithParam = call read('classpath:com/karate/openwhisk/functions/greetings.js')
  
   Scenario: As a user i want to verify rejection of unauthenticated user
   * print 'Test case started--> As a user i want to verify rejection of unauthenticated user'
   	# Get User Auth for guest
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    #create action. Create an action for the above defined guest name
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def actionName = createAction.actName
    * print actionName
    * print "Successfully Created an action"
    
    #create Namespace normaluser
    * def createNamespace = call read('classpath:com/karate/openwhisk/wskadmin/create-namespace.feature') {nameSpace:'#(nameSpace2)'}
    * def NSCred = createNamespace.NScreds
    #get User Auth for normaluser
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace2)'}
    * def result = getNSCreds.result
    * def Auth2 = base64encoding(result)
    * print "Got the Creds for the normaluser"
    * print Auth2
    #Delete the same action in different name space
    * def deleteAction = call read('classpath:com/karate/openwhisk/wskactions/delete-action.feature') {actionName:'#(actionName)' ,nameSpace:'#(nameSpace2)' ,Auth:'#(Auth2)'}
    * match deleteAction.response.error == "The requested resource does not exist."    
     
   
    
    
    
    
    
    
    