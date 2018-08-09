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
# Summary :This feature file will get the list of action based on the namespace provided
#Author: rtripath@adobe.com
# Summary :This feature file can be used to get action destils using action name
@createNS
Feature: Get User Credentials

Background:
* configure ssl = true
  @ignore
  Scenario: Get NS credentials
  
  #generate UUID
    * def keyMaker =  
    """
    function makeid() {
      var text = "";
      var possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

      for (var i = 0; i < 64; i++)
           text += possible.charAt(Math.floor(Math.random() * possible.length));
					return text;
			}
		
    """ 
    * def nskey = keyMaker()
    * print 'Key is :' + nskey
    * def nsUUID = java.util.UUID.randomUUID()
    * print 'UUID to create namespace is :' + nsUUID
    * string xyz = nsUUID
    * def requestBody = {"_id": '#(nameSpace2)', "namespaces": [{"name": '#(nameSpace2)', "key": '#(nskey)', "uuid": '#(xyz)' }], "subject": '#(nameSpace2)'}
    * string payload = requestBody
    Given url AdminBaseUrl
    * print "I am here in get-user"
   # And path '/whisk_local_subjects/'+nameSpace
   And path '/local_subjects/'+nameSpace
    And header Authorization = AdminAuth
    And header Content-Type = 'application/json'
    When method get
    #Then status 404
    Given url AdminBaseUrl
    * print "I am here in create-user"
     And path '/whisk_local_subjects'
     And header Authorization = AdminAuth
    And header Content-Type = 'application/json'
    And request payload
    When method post
    * def responseCode = responseStatus
    * eval
    """
    if(responseCode == 201) {
    	 karate.log("User Created");
    	 }
    else if(responseCode == 409){
       karate.log("User already exists");
       }
    """
    * print 'User gets created'
    And string NScreds = response
    * print NScreds

 
