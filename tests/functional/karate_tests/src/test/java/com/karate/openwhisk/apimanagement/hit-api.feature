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