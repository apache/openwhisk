#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.
#Summary :This feature file will check the perf of a single API
@perftest
Feature: This feature file will test the performace of DB bit hitting the DB

  Background: 
    * configure ssl = true
    * def nameSpace = 'guest'
    * def params = '?blocking=true&result=false'
    * def scriptcode = call read('classpath:com/karate/openwhisk/functions/hello-world.js')
    * def Auth = read('classpath:com/karate/openwhisk/utils/authFile.txt')
    * print 'Auth is: ' + Auth

  Scenario: TC041-As a user I want to all hit some load on the DB hitting the GetUser Creds API and validate that the Response does stays below the benchmarked limits

    #create action. Create an action for the above defined guest name
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)', Auth:'#(Auth)'}
    * def actionName = createAction.actName
    * match createAction.responseStatusCode == 200
    * print "Successfully Created an action"
    