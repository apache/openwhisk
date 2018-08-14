#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.
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