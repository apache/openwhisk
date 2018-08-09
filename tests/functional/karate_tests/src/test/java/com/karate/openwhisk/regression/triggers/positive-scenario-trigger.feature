#/*
 #*  Copyright 2017-2018 Adobe.
 #*
 #*  Licensed under the Apache License, Version 2.0 (the "License");
 #*  you may not use this file except in compliance with the License.
 #*  You may obtain a copy of the License at
 #*
 #*          http://www.apache.org/licenses/LICENSE-2.0
 #*
 #*  Unless required by applicable law or agreed to in writing, software
 #*  distributed under the License is distributed on an "AS IS" BASIS,
 #*  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 #*  See the License for the specific language governing permissions and
 #*  limitations under the License.
 #*/
#Author: mamishra@adobe.com
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
    
    
    
    