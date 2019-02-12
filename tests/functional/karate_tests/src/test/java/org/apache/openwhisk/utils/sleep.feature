#Licensed to the Apache Software Foundation (ASF) under one or more contributor
#license agreements; and to You under the Apache License, Version 2.0.
@ignore
Feature: Used to Sleep between tests. sheepCount to (default 60 sec, pass '-1' to avoid) sleep as many seconds

  Background: 
    * def sleep =
      """
      function() {
        var seconds = karate.get('sheepCount');
        if (seconds) {  // check if sheepCount is defined
          if (seconds == -1) {
            karate.log("NO Sleeping: as we were told not to count sheep");
            return;
          }
        } else {
          seconds = 60; // default sleep time in seconds
          karate.log("Use default sleep time: " + seconds);
        }
        karate.log("Sleeping for " + seconds );
        for(i = 1; i <= seconds; i++) {
          java.lang.Thread.sleep(1*1000);
      //karate.log("counting sheep - " + i);
      //karate.log("Done sleeping, back to work...");
        }
       
      }
      """

  Scenario: This line is required please do not delete - or the functions cannot be called
    * print "I cam here"
    * call sleep
