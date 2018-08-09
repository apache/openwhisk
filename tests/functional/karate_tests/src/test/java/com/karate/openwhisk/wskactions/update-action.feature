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
# Summary :This feature file will update the action.We need to provide the action name and the function to update the action

@ignore
Feature: Update an action
  
  Background:
* configure ssl = true


  Scenario: As a user I want to update the action and give the updated response
    #Update an Action
    * def requestBody = {"namespace":'#(nameSpace)',"name":'#(actionName)',"exec":{"kind":"nodejs:default","code":'#(script)'}}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/actions/'+actionName+'?overwrite=true'
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method put
    Then status 200
    And def actName = response.name
     * print 'Action name for the created action ' + actName
    