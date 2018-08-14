#// Licensed to the Apache Software Foundation (ASF) under one or more contributor
#// license agreements; and to You under the Apache License, Version 2.0.
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
    
    
  
