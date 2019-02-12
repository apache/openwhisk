function() {
// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.
    var env = karate.env; // get system property 'karate.env'
    var adminauth = karate.properties['adminauth'];
    var adminbaseurl = karate.properties['adminbaseurl'];
    var baseurl = karate.properties['baseurl'];
    karate.log('karate.env system property was:', env);

    if (!adminauth) {

        adminauth='d2hpc2tfYWRtaW46c29tZV9wYXNzdzByZA=='

    }

    if (!adminbaseurl) {

        adminbaseurl='http://localhost:5984'



    }
    if (!baseurl) {

        baseurl='https://localhost:443'


    }

    var config = {
            env: env,
            adminauth:adminauth,
            baseurl:baseurl,
            adminbaseurl:adminbaseurl


    }

        // Admin Config
        config.AdminAuth="Basic " +adminauth,
        config.AdminBaseUrl=adminbaseurl,
        config.BaseUrl=baseurl

        return config;
}
