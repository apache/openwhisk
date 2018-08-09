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
#@ignore
Feature: Create Namespace

  Background:
    * configure ssl = true
    * def nameSpace = 'guest'
    * def base64encoding = read('classpath:com/karate/openwhisk/utils/base64.js')
    #* def authFile = '/com/karate/openwhisk/utils'

  Scenario: Get NS credentials
    Given url AdminBaseUrl
    * print "I am here in get-user"
    * def DBpath =
    """
   
    if (BaseUrl.match ('rtbeta'))
    {
     DBpath = '/whisk_dev_subjects/';
    }
    else{
     DBpath = '/local_subjects/';
    }
    
    """
    #And path '/whisk_local_subjects/'+nameSpace
    And path DBpath+nameSpace
    And header Authorization = AdminAuth
    And header Content-Type = 'application/json'
    When method get
    Then status 200
    And string NScreds = response
    * def uuid = $response.namespaces[*].uuid
    * def key = $response.namespaces[*].key
    * def result = uuid[0]+':'+ key[0]
    * def Auth = base64encoding(result)
    #* def uuid = $response.namespaces[*].uuid
    * print result
    * def FileUtil = Java.type('com.karate.openwhisk.utils.OWFileUtil')
    * string fileSaveResult = FileUtil.writeToFile(Auth, 'authFile.txt')
    * assert fileSaveResult == 'success'
    

 
