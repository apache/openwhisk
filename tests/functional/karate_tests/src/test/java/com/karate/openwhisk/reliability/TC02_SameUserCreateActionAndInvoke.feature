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
#Author: rtripath@adobe.com
# Summary :This feature file will check for the containers
@reliability


Feature:  Invoke same action from User 1 so that a new container is used on each invocation

  Background:
* configure ssl = true
* def testactivations = read('classpath:com/karate/openwhisk/reliability/loop-invokeaction-new-container.js')


  Scenario: TC02-As a user I want to invoke an action and check that on action execution a new container should spawn up
  
  #Create an Action
     * def scriptcode = call read('classpath:com/karate/openwhisk/functions/sleep-function.js')
     * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)',nameSpace:'#(NS_botTester1)' ,Auth:'#(Auth_botTester1)'}
     * def actionName = createAction.actName
     * print actionName
     
     
  #Invoke Action Twice and check that a new container should spawn up
  * def nameSpace = NS_botTester1
  * def Auth = Auth_botTester1
  * def result = testactivations('classpath:com/karate/openwhisk/wskactions/invoke-action.feature',actionName,1,nameSpace ,Auth) 
     

     

 