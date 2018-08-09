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
#this feature is for get the details of the a rule 

@ignore
Feature: Get a Rule details
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true
	
	Scenario: As a user i want to get the details of a rule
		Given url BaseUrl
    And path '/api/v1/namespaces/'+nameSpace+'/rules/'+ruleName
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    When method get
    * def responseStatusCode = responseStatus
    * def rulName = response.name
    * print 'The value of responseStatusCode is:',responseStatusCode
    * eval 
    """
    if(responseStatusCode==200){
    	 karate.log("Got the rule details");
    	 karate.set('rule_details', response)
    	 }
    else if(responseStatusCode == 404){
       karate.log("The requested resource does not exist.");
       }
    """
