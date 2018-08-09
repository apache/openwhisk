function() {
    var env = karate.env; // get system property 'karate.env'
    var adminauth = karate.properties['adminauth'];
    var adminbaseurl = karate.properties['adminbaseurl'];
    var baseurl = karate.properties['baseurl'];

    karate.log('karate.env system property was:', env);

    if (!adminauth) {

        adminauth='d2hpc2tfYWRtaW46YmxhZGVydW5PcHM='

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


    // Bot Details
    config.NS_botTester1="tester1",
    config.Auth_botTester1="Basic ODg5M2Q5YjYtY2E1Yy00OTQ1LTk2Y2EtZDJmY2MxMDQzM2ZkOmFBNzc3ZkZ6NURoaUt3dnVwVndtT05XckozYlpMb0ZZMVJHekQwUkVvYXJVUXBTcURBT3BTV092bTM5Y2dVRjg=",
    config.NS_botTester2="tester2",
    config.Auth_botTester2="Basic MDI0OTE0YTYtYzAyNy00MTFjLTkwOWMtOGIxYzkyZWUxNGFiOmZEQ3p6TGxqVlF6Slo1WFh4OHBGeThiNlpybDAxS3g1WWZFQm81dUF5S0JCZEo2MWxDUFhvRGtyVmNDeGZrOXM=",
    config.NS_botTester3="tester3",
    config.Auth_botTester3="Basic MDU2OTY1MDctNDEyMC00ZTczLWEwNzQtMmUzODUyM2UzNmJmOlN4WUJRM0JzNFJ3Mjh5VTE0emtFdnZOdnEwUWtHY0hBbTNzakg1bXY0QjdTblB3UFVQQnZ5WGxTSXI3QUM4dGc=",
    config.NS_botTester4="tester4",
    config.Auth_botTester4="Basic MDdlOTI4MmMtNmY5Zi00OWFjLTg5NzctOTVjMTIwMTA1NmJlOkJpRlN0cEZFZHdmdTlhTDdQY0tvdTdocUFHM3FzMzdhU1dvMTZwMVBHWmJ3QWc4elpVZjkwQkcxa1gzdjdlWXA=",
    config.NS_botTester5="tester5",
    config.Auth_botTester5="Basic MzIwNjAxYTMtZTJiMS00NzNhLTgxNmMtYTBiZGFmNTFjMGQ3OmZ6aTcxeWNUVjRyYVlWMUI1ZmRwZUVrSHFkSXdCNkxEVTJ6RVI4em14dFAwbTdERlBBYXVaZEpHcXRqdnUyaVA=",
    config.NS_botTester6="tester6",
    config.Auth_botTester6="Basic MWEwMTVhY2EtODUyYy00YmU3LTk0OWYtMTkyYzVkYWRiMGFmOlhsdnlQanpSblRKOVk5djRjSmpyZDZNVm91QTlwdnJ2cEJKYXpTRmV5amxHcm1ESVBYRk44aEYzQzNoRXdIamc=",
    config.NS_botTester7="tester7",
    config.Auth_botTester7="Basic OWZmOTRlNjEtYmUzNy00NGZjLTk4ZjgtNDUyYTFlNWVmMGU2OjE2M0IxVVlJOVFna3V6YXlJcHp1cGVLT1Izek1NYnJVbU1oTTR6QlhQR0dMYmxOdGNnVmVDdDdtb2swd2lJRFM=",
    config.NS_botTester8="tester8",
    config.Auth_botTester8="Basic MzNjMWY1ZmMtM2ViZC00NGM3LTk0ZTYtZWRkYzAxMDgxYjc2OnpCZkFjRjNJTjY2QXRDWVNHMzlraTY5ODROOFFRcGZzdmpBYVZYZk5ab0EwTHpWSm9NUm41YTZVRGMwT1NOVXM=",
    config.NS_botTester9="tester9",
    config.Auth_botTester9="Basic MDA0MGYyZWYtMGEwZi00NDkwLTllMDctMjc1OWJlZGM4YmVlOkJ5RU9DQXdrcDFGQmxHMkYwSm56ZUpFZTF0Ymt4b3VwZ21UYThwN2xsVWNJUFZ6Z0NrRUMySkZ5blJUSGRPT1o=",
    config.NS_botTester10="tester10",
    config.Auth_botTester10="Basic YTczNTViMDctY2U1OS00Y2E0LThmYjYtNmUxYzAzNmQ3MTE2OkRMeU1RTjlXeTBPOE5NeGRNandJV3NlV0U2WWUzUGthY1BGU2V2M252Qko1TWNYQXhBbW81U3AxdWNmVFBWRVo="

        // Admin Config
        config.AdminAuth="Basic " +adminauth,
        config.AdminBaseUrl=adminbaseurl,
        config.BaseUrl=baseurl

        //	config.AdminAuth="Basic d2hpc2tfYWRtaW46c29tZV9wYXNzdzByZA==",
        //   config.AdminBaseUrl="http://172.17.0.1:5984",
//      config.BaseUrl="https://172.17.0.1:443"

        return config;
}



/**
 *  Copyright 2017-2018 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Rahul Tripathi
 *
 *
 */
