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
#Summary :This feature file will 1)Import the swagger file 2)Get the list of API's 3)Hit each API and assert on 200 OK
@smoketests


Feature: This feature file will test the basic API Management Functionality

  Background: 
    * configure ssl = true
    * def nameSpace = 'guest'
    * def params = '?blocking=true&result=false'
    * def scriptcodeget = call read('classpath:com/karate/openwhisk/functions/getResponse.js')
    * def scriptcodepost = call read('classpath:com/karate/openwhisk/functions/postResponse.js')
    * def scriptcodeput = call read('classpath:com/karate/openwhisk/functions/putResponse.js')
    * def scriptcodedelete = call read('classpath:com/karate/openwhisk/functions/deleteResponse.js')
    * def base64encoding = read('classpath:com/karate/openwhisk/utils/base64.js')
    * string raw_swagger = read('classpath:com/karate/openwhisk/utils/pet-store-swagger.json')
    * def webAction = 'true'
       * table apis
    | endpoint   | methodtype |
    | '/apis/guest/v2/pet/test123'  | 'get'|
    |'/apis/guest/v2/pet/test123'   |'post'|
    |'/apis/guest/v2/pet/test123'   |'delete'|
    |'/apis/guest/v2/pet'   |'post'|
    |'/apis/guest/v2/pet'   |'put'|
    |'/apis/guest/v2/pet/findByStatus'   |'get'|
    |'/apis/guest/v2/store/order'        |'post'|
    |'/apis/guest/v2/store/order/test234' |'get'|
    |'/apis/guest/v2/store/order/test234' |'delete'|
    |'/apis/guest/v2/user/logout'         |'get'|
    |'/apis/guest/v2/user'                |'post'|
    |'/apis/guest/v2/user/login'          |'get'|
    |'/apis/guest/v2/pet/test123/uploadImage'  |'post'|
    |'/apis/guest/v2/user/createWithArray'    |'post'|
    |'/apis/guest/v2/pet/findByTags'          |'get'|
    |'/apis/guest/v2/store/inventory'         |'get'|
    |'/apis/guest/v2/user/createWithList'     |'post'|
    |'/apis/guest/v2/user/tester'             |'get'|
    |'/apis/guest/v2/user/tester'             |'put'|
    |'/apis/guest/v2/user/tester'             |'delete'|
    
    
    
  Scenario: TC03-As a user I want to import my swagger.json and see if my API gives a Two Hundred OK response
    
     # Get User Guid & Auth
    * def getNSCreds = call read('classpath:com/karate/openwhisk/wskadmin/get-user.feature') {nameSpace:'#(nameSpace)'}
    * def result = getNSCreds.result
    * def Auth = base64encoding(result)
    * def guid = getNSCreds.uuid[0]
    
    # Create an Action .Create an 4 actions for the above defined guest name.This will be used by the API's
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcodeget)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)' , actionName: 'getResponse' , webAction: '#(webAction)'}
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcodepost)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)', actionName: 'postResponse' , webAction: '#(webAction)'}
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcodeput)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)' , actionName: 'putResponse' , webAction: '#(webAction)'}
    * def createAction = call read('classpath:com/karate/openwhisk/wskactions/create-action.feature') {script:'#(scriptcodedelete)' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)' ,actionName: 'deleteResponse' , webAction: '#(webAction)'}
    * print "Successfully Created the required actions"
    
    # Call the import Swagger feature file and import the swagger
    * def importSwagger = call read('classpath:com/karate/openwhisk/apimanagement/import-swagger.feature') {nameSpace:'#(nameSpace)' ,Auth:'#(Auth)',guid:'#(guid)' , raw_swagger: '#(raw_swagger)'}
    * print "Successfully imported the swagger"
    
    #Call the get-api-list swagger to get the list of the imported swagger
    * def getSwaggerList = call read('classpath:com/karate/openwhisk/apimanagement/get-api-list.feature') {guid:'#(guid)' ,Auth:'#(Auth)'}
    * print  = "Successfully got the List of API's"
    * def sleepsometime = callonce read('classpath:com/karate/openwhisk/utils/sleep.feature') {sheepCount:'5'}
     * print "Got the List of APIs Hurray!"
   
   #Hit the imnported APIs and asset the response
      * def result = callonce read('classpath:com/karate/openwhisk/apimanagement/hit-api.feature') apis
   
   
      # Call the Delete API feature file to delete the imported API List
    * def deleteSwagger = call read('classpath:com/karate/openwhisk/apimanagement/delete-api-list.feature') {guid:'#(guid)' ,Auth:'#(Auth)'}
    * print "Successfully imported the swagger"
    
    # Delete the list of actions
    * def deleteAction = call read('classpath:com/karate/openwhisk/wskactions/delete-action.feature') {actionName:'getResponse' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def deleteAction = call read('classpath:com/karate/openwhisk/wskactions/delete-action.feature') {actionName:'postResponse' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def deleteAction = call read('classpath:com/karate/openwhisk/wskactions/delete-action.feature') {actionName:'putResponse' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * def deleteAction = call read('classpath:com/karate/openwhisk/wskactions/delete-action.feature') {actionName:'deleteResponse' ,nameSpace:'#(nameSpace)' ,Auth:'#(Auth)'}
    * print "Successfully deleted all the actions"
    
    