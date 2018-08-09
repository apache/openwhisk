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
@ignore
Feature: Import Swagger.json

 Background: 
    * configure ssl = true
    * def nameSpace = 'guest' 
    * def stringify =  read('classpath:com/karate/openwhisk/utils/swagger-stringify.js')
    * def base64encoding = read('classpath:com/karate/openwhisk/utils/base64.js')
    
  Scenario: Import Swagger.json from the utils and print the output as a string    
    ## Below can be passed to any request as a request
    * def payload = stringify(raw_swagger)
     Given url BaseUrl + '/api/v1/web/whisk.system/apimgmt/createApi.http?accesstoken=DUMMY+TOKEN&responsetype=json&spaceguid='+guid
     And header Authorization = Auth
     And header Content-Type = 'application/json'
     And request payload
     When method post
     Then status 200