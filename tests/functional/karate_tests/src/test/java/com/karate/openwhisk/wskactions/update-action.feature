#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

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
    