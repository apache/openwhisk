#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.
@apimanagement
@ignore
Feature: Hit the End Points and Assert for Success

 Background: 
    * configure ssl = true
  
    
  Scenario: Hit the End Points and assert of they give a two hundred OK 
     Given url BaseUrl + endpoint
     And header Authorization = Auth
     And header Content-Type = 'application/json'
     		* eval
 		 """
					if (typeof methodtype == 'post'||'put'||'delete') {
					    karate.set('payload', '');
					} else {
							karate.set('payload', '');
					}
 		 """
 		 And request payload
     When method methodtype
     Then status 200