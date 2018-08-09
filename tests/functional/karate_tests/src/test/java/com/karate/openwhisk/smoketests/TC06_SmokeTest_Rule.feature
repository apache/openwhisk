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
    
   #@ignore  
  Scenario: As a user i want to verify create rule, get rule, update rule,list rule and delete rule
   	* print "Test case started --> verify create rule, get rule, update rule,list rule and delete rule" 
    #create a trigger
    * def createTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/create-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * match createTrigger.responseStatusCode == 200
    * def triggerName = createTrigger.trgrName
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
    * def ruleName = createRule.rulName
    * match createRule.responseStatusCode == 200
    * print 'successfully created the rule'
    #get the rule
    * def getRule = call read('classpath:com/karate/openwhisk/wskrules/get-rule.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',ruleName:'#(ruleName)'}
    * match getRule.responseStatusCode == 200
    * def actualRuleName = getRule.rulName
    * match actualRuleName == ruleName
    * print "Asserted "+actualRuleName+" with " + ruleName
    * print "Successfully got the rule details"
    
    #update the rule
    # create another trigger to update the rule
    * def trgrName1 = 'Trigger'+java.util.UUID.randomUUID()
    * def createTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/create-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)', triggerName:'#(trgrName1)'}
    * def triggerName1 = createTrigger.trgrName
    * match createTrigger.responseStatusCode == 200
    * print "Successfully Created an trigger"
    #create another action to update the rule
    * def actName1 = 'Testing'+java.util.UUID.randomUUID()
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {actionName:'#(actName1)', script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def actionName1 = createAction.actName
    * match createAction.responseStatusCode == 200
    * print "Successfully Created an action"
    #now updating the rule
    * def trgrName = '/'+nameSpace +'/'+triggerName1
    * def actName = '/'+nameSpace +'/'+actionName1
    * def updateRule = call read('classpath:com/karate/openwhisk/wskrules/update-rule.feature') {ruleName:'#(ruleName)', triggerName:'#(trgrName)', actionName:'#(actName)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def ruleName = updateRule.rulName
    * print ruleName
    * def actualTriggerName = updateRule.updateRuleResponse.trigger.name
    * match actualTriggerName == trgrName1
    * print "Asserted "+actualTriggerName+" with " + trgrName1
    * def actualActionName = updateRule.updateRuleResponse.action.name
    * match actualActionName == actName1
    * print "Asserted "+actualActionName+" with " + actName1
    * print "Successfully updated the rule"
    # List rules
    * def listRules = call read('classpath:com/karate/openwhisk/wskrules/list-rule.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * print "Successfully pulled up the list of rules"
    # Delete the Rule
    * def deleteRule = call read('classpath:com/karate/openwhisk/wskrules/delete-rule.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',ruleName:'#(ruleName)'}
    * match deleteRule.responseStatusCode == 200
    * print "Test case completed --> verify create rule, get rule, update rule,list rule and delete rule"
    
    #@ignore 
  Scenario: As a user i want to verify create rule, get rule, ensure rule is enabled by default
    * print "Test case started --> verify create rule, get rule, ensure rule is enabled by default"
     #create a trigger
    * def createTrigger = call read('classpath:com/karate/openwhisk/wsktriggers/create-trigger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * match createTrigger.responseStatusCode == 200
    * def triggerName = createTrigger.trgrName
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
    * def ruleName = createRule.rulName
    * match createRule.responseStatusCode == 200
    * print 'successfully created the rule'
    * def ruleStatus = createRule.createRuleResponse.status
    * match ruleStatus == "active"
    * print "Asserted "+ruleStatus+" with active"
    * print "Test case completed --> verify create rule, get rule, ensure rule is enabled by default"
    
    