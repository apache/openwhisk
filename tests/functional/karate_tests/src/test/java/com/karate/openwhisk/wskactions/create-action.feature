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
# Summary :This feature file can be used to create actions
@ignore

Feature: Create an Action
  I want to use this template for my feature file
  
  Background:
* configure ssl = true


  Scenario: As a user I want to create an action
    
 #Create an Action
 		* eval
 		 """
					if (typeof actionName == 'undefined') {
					    karate.set('actionName', 'Testing'+java.util.UUID.randomUUID());
					} else {
							karate.set('actionName', actionName);
					}
 		 """
 		 
 			* eval
 		 """
					if (typeof webAction == 'undefined') {
					    karate.set('webAction', 'false');
					} else {
							karate.set('webAction', 'true');
					}
 		 """
 		 
    * def requestBody = {"namespace":'#(nameSpace)',"name":'#(actionName)',"exec":{"kind":"nodejs:default","code":'#(script)'},"annotations":[{"key":"web-export","value":true},{"key":"raw-http","value":false},{"key":"final","value":true}]}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/actions/'+actionName+'?overwrite=false'
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method put
    * def responseStatusCode = responseStatus
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval
    """
    if(responseStatusCode == 200) {
    	 karate.log("Action Created");
    	 }
    else if(responseStatusCode == 409){
       karate.log("Duplicate Action");
       }
    """
    #* match responseStatusCode == 200
    * eval if(responseStatusCode == 200) karate.set('actName', response.name )
    * print 'Action name for the created action ' + actName
    
    
    
  