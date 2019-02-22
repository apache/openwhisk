# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

# Summary :This feature file can be used to get action destils using action name
@ignore

Feature: Create Namespace

  Background:
    * configure ssl = true
    * def nameSpace = 'guest'
    * def base64encoding = read('classpath:org/apache/openwhisk/utils/base64.js')


  Scenario: Get NS credentials
    Given url AdminBaseUrl
    * print "I am here in get-user"
    * def DBpath = '/local_subjects/'
    And path DBpath+nameSpace
    And header Authorization = AdminAuth
    And header Content-Type = 'application/json'
    When method get
    Then status 200
    And string NScreds = response
    * def uuid = $response.namespaces[*].uuid
    * def key = $response.namespaces[*].key
    * def result = uuid[0]+':'+ key[0]
    * print result
