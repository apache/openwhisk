<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->

# Utility Scripts

This module is a collection of few utility scripts for OpenWhisk development. The scripts
can be invoked as gradle tasks. Depending on your current directory the gradle command would
change

With current directory set to OpenWhisk home

    ./gradlew :tools:dev:<taskName>

With this module being base directory

    ../../gradlew <taskName>

## couchdbViews

Extracts and dump the design docs js in readable format. It reads all the design docs from
_<OPENWHISH_HOME>/ansibles/files_ and dumps them in _build/views_ directory

Sample output

    $./gradlew :tools:dev:couchdbViews
    Processing whisks_design_document_for_entities_db_v2.1.0.json
            - whisks.v2.1.0-rules.js
            - whisks.v2.1.0-packages-public.js
            - whisks.v2.1.0-packages.js
            - whisks.v2.1.0-actions.js
            - whisks.v2.1.0-triggers.js
    Processing activations_design_document_for_activations_db.json
            - activations-byDate.js
    Processing auth_design_document_for_subjects_db_v2.0.0.json
            - subjects.v2.0.0-identities.js
    Processing filter_design_document.json
    Processing whisks_design_document_for_activations_db_v2.1.0.json
            - whisks.v2.1.0-activations.js
    Skipping runtimes.json
    Processing logCleanup_design_document_for_activations_db.json
            - logCleanup-byDateWithLogs.js
    Processing whisks_design_document_for_all_entities_db_v2.1.0.json
            - all-whisks.v2.1.0-all.js
    Processing whisks_design_document_for_activations_db_filters_v2.1.1.json
            - whisks-filters.v2.1.1-activations.js
    Generated view json files in /path/too/tools/build/views

## IntelliJ Run Config Generator

This script enables creation of [Intellij Launch Configuration][1] in _<openwhisk home>/.idea/runConfigurations_
with name controller0 and invoker0. For this to work your Intellij project should be [directory based][3]. If your
project is file based (uses ipr files) then you can convert it to directory based via _File -> Save as Directory-Based Format_. These run configurations can then be invoked from _Run -> Edit Configurations -> Application_

### Usage

First setup OpenWhisk so that Controller and Invoker containers are up and running. Then run the script:

    ./gradlew :tools:dev:intellij

It would inspect the running docker containers and then generate the launch configs with name 'controller0'
and 'invoker0'.

Now the docker container(s) (controller and/or invoker) can be stopped and they can be launched instead from within the IDE.

Key points to note:

1. Controller uses port `10001` and Invoker uses port `12001`.
2. Action activation logs are [disabled][2].
3. SSL is disabled for Controller and Invoker.
4. Make sure you have the loopback interface configured:
   ```bash
   sudo ifconfig lo0 alias 172.17.0.1/24
   ```
5. `~/.wskprops` must be updated with `APIHOST=http://localhost:10001` so that the `wsk` CLI communicates directly with the controller.
6. On a MAC
   * With Docker For Mac the invoker is configured to use a Container Factory that exposes ports for actions on the host,
     as otherwise the invoker can't make HTTP requests to the actions.
     You can read more at [docker/for-mac#171][7].

   * When using [docker-compose][8] locally you have to update `/etc/hosts` with the line bellow:
      ```
      127.0.0.1       kafka zookeeper kafka.docker zookeeper.docker db.docker controller whisk.controller
      ```


### Configuration

The script allows some local customization of the launch configuration. This can be done by creating a [config][4] file
`intellij-run-config.groovy` in project root directory. Below is an example of _<openwhisk home>/intellij-run-config.groovy_
file to customize the logging and db port used for CouchDB.

```groovy
//Configures the settings for controller application
controller {
    //Base directory used for controller process
    workingDir = "/path/to/controller"
    //System properties to be set
    props = [
            'logback.configurationFile':'/path/to/custom/logback.xml'
    ]
    //Environment variables to be set
    env = [
            'DB_PORT' : '5989',
            'CONFIG_whisk_controller_protocol' : 'http'
    ]
}

invoker {
    workingDir = "/path/to/invoker"
    props = [
            'logback.configurationFile':'/path/to/custom/logback.xml'
    ]
    env = [
            'DB_PORT' : '5989'
    ]
}

```

The config allows following properties:

* `workingDir` - Base directory used for controller or invoker process.
* `props` - Map of system properties which should be passed to the application.
* `env` - Map of environment variables which should be set for application process.

## Github Repository Lister

Lists all Apache OpenWhisk related repositories by using [Github Search API][5] with pagination. Its preferable that prior
to using this you specify a [Github Access Token][6] as otherwise requests will quickly become rate limited. The token
can be specified by setting environment variable `GITHUB_ACCESS_TOKEN`

```bash
$ ./gradlew :tools:dev:listRepos
Found 44 repositories
openwhisk
openwhisk-GitHubSlackBot
openwhisk-apigateway
openwhisk-catalog
...
Stored the list in /openwhisk_home/build/repos/repos.txt
Stored the JSON details in /openwhisk_home/build/repos/repos.json

```

It generates 2 files

* `repos.txt` - List repository names one per line.
* `repos.json` - Stores an array of repository details JSON containing various repository related details.

## OpenWhisk Module Status Generator

It renders a markdown file which lists the status of various OpenWhisk modules by using the output generated by `listRepos`
task. The rendered markdown file is stored in `docs/dev/modules.md`. This rendered file should be later checked in.

```bash
$ ./gradlew :tools:dev:renderModuleDetails

  > Task :tools:dev:renderModuleDetails
  Generated modules details at /openwhisk_home/docs/dev/modules.md

```

[1]: https://www.jetbrains.com/help/idea/run-debug-configurations-dialog.html#run_config_common_options
[2]: https://github.com/apache/openwhisk/issues/3195
[3]: https://www.jetbrains.com/help/idea/configuring-projects.html#project-formats
[4]: http://docs.groovy-lang.org/2.4.2/html/gapi/groovy/util/ConfigSlurper.html
[5]: https://developer.github.com/v3/search/
[6]: https://help.github.com/articles/creating-a-personal-access-token-for-the-command-line/
[7]: https://github.com/docker/for-mac/issues/171
[8]: https://github.com/apache/openwhisk-devtools/tree/master/docker-compose
