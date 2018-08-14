#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.

#this feature is to list all the triggers

@ignore
Feature:  Get List of triggers based on the NameSpace

  Background:
* configure ssl = true


  Scenario: As a user I want to get the list of triggers available for the given namespace
    * def path = '/api/v1/namespaces/'+nameSpace+'/triggers?limit=30&skip=0'
    Given url BaseUrl+path
    And header Authorization = Auth
    And header Content-Type = 'application/json'
    When method get
    Then status 200
    And def json = response
