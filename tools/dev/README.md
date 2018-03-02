<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more contributor 
# license agreements.  See the NOTICE file distributed with this work for additional 
# information regarding copyright ownership.  The ASF licenses this file to you
# under the Apache License, Version 2.0 (the # "License"); you may not use this 
# file except in compliance with the License.  You may obtain a copy of the License 
# at:
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed 
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR 
# CONDITIONS OF ANY KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations under the License.
#
-->

# Utility Scripts

This module is a collection of few utility scripts for OpenWhisk development. The scripts
can be invoked as gradle tasks. Depending on your current directory the gradle command would
change

With current directory set to OpenWhisk home

    ./gradlew -p tools/dev <taskName>
    
With this module being base directory

    ../../gradlew <taskName>

## couchdbViews

Extracts and dump the design docs js in readable format. It reads all the design docs from 
_<OPENWHISH_HOME>/ansibles/files_ and dumps them in _build/views_ directory

Sample output

    $./gradlew -p tools/dev couchdbViews
    Processing whisks_design_document_for_entities_db_v2.1.0.json
            - whisks.v2.1.0-rules.js
            - whisks.v2.1.0-packages-public.js
            - whisks.v2.1.0-packages.js
            - whisks.v2.1.0-actions.js
            - whisks.v2.1.0-triggers.js
    Processing activations_design_document_for_activations_db.json
            - activations-byDate.js
    Processing auth_index.json
            - subjects-identities.js
    Processing filter_design_document.json
    Processing whisks_design_document_for_activations_db_v2.1.0.json
            - whisks.v2.1.0-activations.js
    Skipping runtimes.json
    Processing logCleanup_design_document_for_activations_db.json
            - logCleanup-byDateWithLogs.js
    Processing whisks_design_document_for_all_entities_db_v2.1.0.json
            - all-whisks.v2.1.0-all.js
    Processing whisks_design_document_for_activations_db_filters_v2.1.0.json
            - whisks-filters.v2.1.0-activations.js
    Generated view json files in /path/too/tools/build/views
