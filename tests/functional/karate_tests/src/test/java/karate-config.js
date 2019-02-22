function() {
// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.
   var env = karate.env; // get system property 'karate.env'
   var adminauth = karate.properties['adminauth'];
   var adminbaseurl = karate.properties['adminbaseurl'];
   var server = karate.properties['server'];

    karate.log('karate.env system property was:', env);
    karate.log('karate.adminauth system property was:', adminauth);
    karate.log('karate.adminbaseurl system property was:', adminbaseurl);
    karate.log('karate.server system property was:', server);

    var config = {
            env: env,
            adminauth: adminauth,
            server: server,
            adminbaseurl: adminbaseurl
    }

        // Admin Config
        config.AdminAuth="Basic " +adminauth,
        config.AdminBaseUrl=adminbaseurl,
        config.BaseUrl=server

        return config;
}
