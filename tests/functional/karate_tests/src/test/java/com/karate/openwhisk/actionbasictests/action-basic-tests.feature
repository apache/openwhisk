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
#Keywords Summary : This feature is all about basic test cases of Actions
@basictests
Feature: This feature contains basic test cases of openwhisks actions

  Background: 
    * configure ssl = true
    * def nameSpace = 'guest'
    * def nameSpace2 = 'normaluser'
    * def params = '?blocking=true&result=false'
    * def scriptcode = call read('classpath:com/karate/openwhisk/functions/hello-world.js')
    * def scriptcodeWithParam = call read('classpath:com/karate/openwhisk/functions/greetings.js')
    * def base64encoding = read('classpath:com/karate/openwhisk/utils/base64.js')
	#@ignore
  Scenario: As a user i want to verify the creation of duplicate entity
  	* print 'Test case started--> verify the creation of duplicate entity'
    # Get User Auth
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    #create action. Create an action for the above defined guest name
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def actionName = createAction.actName
    * match createAction.responseStatusCode == 200
    * print "Successfully Created an action"
    
    #recreate the duplicate action with the same name
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)' ,actionName:'#(actionName)'}
    * def actionName = createAction.actName
    * match createAction.responseStatusCode == 409
    * match createAction.response.error == "resource already exists"
    
		
    Scenario: As a user is want to verify the creation the same action twice with different case letters
    * print 'Test case started--> verify the creation the same action twice with different case letters'
    # Get User Auth for guest
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    #create action. Create an action
    * def UUID = java.util.UUID.randomUUID()
    * def fullName = 'Testing'+ UUID
    * eval 
    """
    var upperCaseName = fullName.toUpperCase();
    karate.set('upperCaseName', upperCaseName);
    var lowerCaseName = fullName.toLowerCase();
		karate.set('lowerCaseName', lowerCaseName);    
    """
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)' ,actionName:'#(upperCaseName)'}
    * def actionName = createAction.actName
    * match createAction.responseStatusCode == 200
    * print actionName
    * print "Successfully Created an action"
    * match actionName == upperCaseName
    * print 'asserted '+actionName+ ' with ' + upperCaseName
    
    #create the same action with different case letter
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)' ,actionName:'#(lowerCaseName)'}
    * def actionName = createAction.actName
    * match createAction.responseStatusCode == 200
    * print actionName
    * print "Successfully Created an action"
    * match actionName == lowerCaseName
    * print 'asserted '+actionName+ ' with ' + lowerCaseName
    * print 'test case completed --> verify the creation the same action twice with different case letters'
    
    #@ignore
    Scenario: As a user i want ro verify create, update, get and list an action
    * print 'Test case started--> verify create, update, get and list an action'
    # Get User Auth
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    
    #create action. Create an action for the above defined guest name
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def actionName = createAction.actName
    * match createAction.responseStatusCode == 200
    * print "Successfully Created an action"
    
    # Update Action
    * def updateAction = call read('classpath:com/karate/openwhisk/wskactions/update-action.feature') {actionName:'#(actionName)',script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def actionName = createAction.actName
    * print actionName
    * print "Successfully updated the action"
    
    # Get Action Details
    * def actionDetails = call read('classpath:com/karate/openwhisk/wskactions/get-action.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',actionName:'#(actionName)'}
    * print "Successfully got the action details"
    
    # List Action
    * def listActions = call read('classpath:com/karate/openwhisk/wskactions/list-action.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * print "Successfully pulled up the list of actions"
    * print 'Test case completed --> verify create, update, get and list an action'
   
   #@ignore
   Scenario: As a user i want to Verify reject delete of action that does not exist
   	* print 'Test case started--> Verify reject delete of action that does not exist'
   	* def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    
    * def actionName = 'Testing'+java.util.UUID.randomUUID()
    
    # Delete Action
    * def deleteAction = call read('classpath:com/karate/openwhisk/wskactions/delete-action.feature') {actionName:'#(actionName)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * match deleteAction.responseStatusCode == 404
    * print "Test case completed --> Verify reject delete of action that does not exist"
    
    #@ignore
    Scenario: As a user i want to verify reject invocation of action that does not exist
    * print 'Test case started--> verify reject invocation of action that does not exist'
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    
    * def actionName = 'Testing'+java.util.UUID.randomUUID()
    #Invoke the action
    * def invokeAction = call read('classpath:com/karate/openwhisk/wskactions/invoke-action.feature') {params:'#(params)',requestBody:'',nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',actionName:'#(actionName)'}
    * match invokeAction.responseStatusCode == 404
    * print "Test case completed --> verify reject invocation of action that does not exist"
    
    
    #@ignore
    Scenario: As a user i want to verify reject get of an action that does not exist
    * print 'Test case started--> verify reject get of an action that does not exist'
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    
    * def actionName = 'Testing'+java.util.UUID.randomUUID()
    #Get action details
    * def actionDetails = call read('classpath:com/karate/openwhisk/wskactions/get-action.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',actionName:'#(actionName)'}
    * match actionDetails.responseStatusCode == 404
    * print "Test case completed --> verify reject get of an action that does not exist"
    
    #@ignore
    Scenario: As a user i want to verify create, and invoke an action using a parameter file
    * print 'Test case started--> verify create, and invoke an action using a parameter file'
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    
    # create action with param
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcodeWithParam)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def actionName = createAction.actName
    * match createAction.responseStatusCode == 200
    * print "Successfully Created an action"
    
    # invoke the action with param
    * def invokeAction = call read('classpath:com/karate/openwhisk/wskactions/invoke-action.feature') {params:'#(params)',requestBody:'{"name":"Manoj","place":"Bangalore"}',nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',actionName:'#(actionName)'}
    * def actID = invokeAction.activationId
    * match invokeAction.responseStatusCode == 200
    * print "Successfully invoked the action"
    * print "Test case completed --> verify create, and invoke an action using a parameter file"
    
   #@ignore
    Scenario: As a user i want to verify create an action with a name that contains spaces
    * print 'Test case started--> verify create an action with a name that contains spaces'
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * print "Got the Creds for the guest user"
    * print Auth
    #Create the action with name having space
    * def UUID = java.util.UUID.randomUUID()
    * def actionNameWithSpce = 'Testing%20'+ UUID
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {actionName:'#(actionNameWithSpce)', script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def actionName = createAction.actName
    * match createAction.responseStatusCode == 200
    * match actionName == 'Testing '+UUID
    * print 'asserted '+actionName+ ' with '+ 'Testing '+UUID
    * print "Successfully Created an action"
    * print "Test case completed --> verify create an action with a name that contains spaces"
    
    
    

