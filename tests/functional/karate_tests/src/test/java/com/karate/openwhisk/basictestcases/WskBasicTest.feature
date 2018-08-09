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
     
   
    
    
    
    
    
    
    