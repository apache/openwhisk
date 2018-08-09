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
#this feature is about fire a trigger

@ignore
Feature: Fire a Trigger
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true


  Scenario: As a user I want to fire a trigger
  	* string payload = requestBody
    * def requestBody = {}    
    * def path = '/api/v1/namespaces/'+nameSpace+'/triggers/'+triggerName
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method post
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval 
    """
    if(responseStatusCode==202){
    	 karate.log("Trigger got fired");
    	 karate.set('activationId', response.activationId);
    	 }
    else if(responseStatusCode == 404){
       karate.log("The requested resource does not exist.");
       }
    """
    * print 'Activation ID for the fired trigger ' + activationId
