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
#this feature is for creating a trigger

@ignore
Feature: Create a Trigger
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true


  Scenario: As a user I want to create a trigger
  	* eval
 		 """
					if (typeof triggerName == 'undefined') {
					    karate.set('triggerName', 'Trigger'+java.util.UUID.randomUUID());
					} else {
							karate.set('triggerName', triggerName);
					}
 		 """
 		* def requestBody = {"name":'#(triggerName)'}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/triggers/'+triggerName+'?overwrite=false'
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method put
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval
    """
    if(responseStatusCode == 200) {
    	 karate.log("Trigger Created");
    	 karate.set('trgrName', response.name )
    	 }
    else if(responseStatusCode == 409){
       karate.log("Duplicate Trigger");
       }
    """
    * print 'Trigger name for the created trigger ' + trgrName
    
    
    
    