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
@apimanagement
@ignnore
Feature: The feature gets the list of API's from the input base path.This Feature can be run standalone or in sequence with other tests

 Background: 
    * configure ssl = true
    * def nameSpace = 'guest'

    
  Scenario: Import Swagger.json from the utils and print the output as a string    
     Given url BaseUrl + '/api/v1/web/whisk.system/apimgmt/getApi.http?accesstoken=DUMMY+TOKEN&basepath=v2'+ '&spaceguid='+guid
     And header Authorization = Auth
     And header Content-Type = 'application/json'
     When method get
     And string ApiList = response
     Then status 200
     * print "Got the List of APIs Hurray!"