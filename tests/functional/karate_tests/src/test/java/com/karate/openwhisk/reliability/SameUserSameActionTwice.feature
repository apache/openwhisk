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

@ignore
Feature:  Invoke same action from User 1 so that a warmed container is used on each invocation

  Background:
* configure ssl = true


  Scenario: As a user I want to invoke an action and check that on action execution a new container should spawn up
  
    #Invoke First action
    * def requestBody = {}
    * string payload = requestBody
    * def path = '/api/v1/namespaces/'+NS_botTester0+'/actions/sleep?blocking=false&result=false'
    Given url BaseUrl+path
    And header Authorization = Auth_botTester0
    And header Content-Type = 'application/json'
    And request payload
    When method post
    And def activationId = response.activationId
     * print 'Activation ID for the Invoke action ' + activationId
    * def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.js')(1000)
    
    #Get Activation details
    * def path = '/api/v1/namespaces/_/activations/'+activationId
    Given url BaseUrl+path
    And header Authorization = Auth_botTester0
    And header Content-Type = 'application/json'
    When method get
    Then status 200
    And match response !contains {"annotations": [{"key": "initTime"}]}
 
      #Invoke Second action
    * def requestBody = {}
    * string payload = requestBody
    * def path = '/api/v1/namespaces/'+NS_botTester0+'/actions/sleep?blocking=false&result=false'
    Given url BaseUrl+path
    And header Authorization = Auth_botTester0
    And header Content-Type = 'application/json'
    And request payload
    When method post
    And def activationId = response.activationId
     * print 'Activation ID for the Invoke action ' + activationId
    * def webhooks = callonce read('classpath:com/karate/openwhisk/utils/sleep.js')(1000)
   
      
#Get Activation details
    * def path = '/api/v1/namespaces/_/activations/'+activationId
    Given url BaseUrl+path
    And header Authorization = Auth_botTester0
    And header Content-Type = 'application/json'
    When method get
    Then status 200
    And match response !contains '{"annotations": [{"key": "initTime"}]}'
 
  
  
  