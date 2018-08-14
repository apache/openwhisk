#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#this feature is for updating the trigger

@ignore
Feature: Update a Trigger details
  I want to use this template for my feature file
  
  Background:
	* configure ssl = true
	
	Scenario: As a user i want to update a trigger
	  #Update an trigger
    * def requestBody = {"name":"#(triggerName)"}
    * string payload = requestBody
    Given url BaseUrl+'/api/v1/namespaces/'+nameSpace+'/triggers/'+triggerName+'?overwrite=true'
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    And request payload
    When method put
    Then status 200
    And def trgrName = response.name
    * print 'Trigger name for the created trigger ' + trgrName
