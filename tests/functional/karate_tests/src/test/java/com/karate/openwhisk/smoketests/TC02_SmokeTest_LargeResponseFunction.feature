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

@smoketests
@ignore



Feature:  This feature file will download a large image from cc storage

  Background:
* configure ssl = true
* def nameSpace = 'guest'
* def params = '?blocking=true&result=true'
* def scriptcode = call read('classpath:com/karate/openwhisk/functions/getAssetContent.js')
* def base64encoding = read('classpath:com/karate/openwhisk/utils/base64.js')




  Scenario: TC02-As a user I want run a long running funtion
  
  #Get User Auth
  * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
  * def uuid = getNSCreds.response.namespaces[*]
  * def result = uuid[0].uuid+':'+uuid[0].key
  * def Auth = base64encoding(result)
  
  # Create an Action 
     * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcode)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
     * def actionName = createAction.actName
     * print actName
     * print "Successfully Created an action"
  
 #Invoke Action
  * def invokeAction = call read('classpath:com/karate/openwhisk/wskactions/invoke-action.feature') {params:'#(params)',requestBody:'',nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',actionName:'#(actionName)'}
  * def actID = invokeAction.activationId
  * print  = "Successfully invoked the action"
  
  
  # Delete Action   
    * def deleteAction = call read('classpath:com/karate/openwhisk/wskactions/delete-action.feature') {actionName:'#(actionName)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * print "Successfully deleted the action"
     
#Call smoke test file again.
     

 