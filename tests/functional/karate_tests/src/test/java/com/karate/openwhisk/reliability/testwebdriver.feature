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

@driver



Feature:  Invoke same action from User 1 so that a warmed container is used on each invocation

  Background:
#* configure ssl = true
#* def testactivations = read('classpath:com/karate/openwhisk/utils/getUserAccessToken.java')

  Scenario: Test WebDriver
   * def webdriver = Java.type('com.karate.openwhisk.utils.getUserAccessToken')
   * def getauth = webdriver.main()
 #  * string fileSaveResult = FileUtil.
   # * assert fileSaveResult == 'success'
    
    
  
