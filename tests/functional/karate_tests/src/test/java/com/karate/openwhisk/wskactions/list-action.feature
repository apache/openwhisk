#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

# Summary :This feature file will get the list of action based on the namespace provided

@ignore
Feature:  Get List of actions based on the NameSpace

  Background:
* configure ssl = true


  Scenario: As a user I want to get the list of actions available for the given namespace
    * def path = '/api/v1/namespaces/'+nameSpace+'/actions?limit=30&skip=0'
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    When method get
    Then status 200
    And def json = response

   
   