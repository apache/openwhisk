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

# Using OpenWhisk-enabled services

In OpenWhisk, a catalog of packages gives you an easy way to enhance your app with useful capabilities, and to access external services in the ecosystem. Examples of external services that are OpenWhisk-enabled include Cloudant, The Weather Company, Slack, and GitHub.

The catalog is available as packages in the `/whisk.system` namespace. See [Browsing packages](./packages.md#browsing-packages) for information about how to browse the catalog by using the command line tool.

## Existing packages in catalog

| Package | Description |
| --- | --- |
| [/whisk.system/alarms](https://github.com/apache/openwhisk-package-alarms/blob/master/README.md) | Package to create periodic triggers |
| [/whisk.system/cloudant](https://github.com/apache/openwhisk-package-cloudant/blob/master/README.md) | Package to work with [Cloudant NoSQL DB](https://console.ng.bluemix.net/docs/services/Cloudant/index.html) service |
| [/whisk.system/github](https://github.com/apache/openwhisk-catalog/blob/master/packages/github/README.md) | Package to create webhook triggers for [GitHub](https://developer.github.com/) |
| [/whisk.system/messaging](https://github.com/apache/openwhisk-package-kafka/blob/master/README.md) | Package to work with [Message Hub](https://console.ng.bluemix.net/docs/services/MessageHub/index.html) service |
| [/whisk.system/pushnotifications](https://github.com/apache/openwhisk-package-pushnotifications/blob/master/README.md) | Package to work with [Push Notification](https://console.ng.bluemix.net/docs/services/mobilepush/index.html) service |
| [/whisk.system/slack](https://github.com/apache/openwhisk-catalog/blob/master/packages/slack/README.md) | Package to post to the [Slack APIs](https://api.slack.com/) |
| [/whisk.system/watson-translator](https://github.com/apache/openwhisk-catalog/blob/master/packages/watson-translator/README.md) | Package for [text translation and language identification](https://www.ibm.com/watson/developercloud/language-translator.html) |
| [/whisk.system/watson-speechToText](https://github.com/apache/openwhisk-catalog/blob/master/packages/watson-speechToText/README.md) | Package to convert [speech into text](https://www.ibm.com/watson/developercloud/speech-to-text.html) |
| [/whisk.system/watson-textToSpeech](https://github.com/apache/openwhisk-catalog/blob/master/packages/watson-textToSpeech/README.md) | Package to convert [text into speech](https://www.ibm.com/watson/developercloud/text-to-speech.html) |
| [/whisk.system/weather](https://github.com/apache/openwhisk-catalog/blob/master/packages/weather/README.md) | Package to work with [Weather Company Data](https://console.ng.bluemix.net/docs/services/Weather/index.html) service |
| [/whisk.system/websocket](https://github.com/apache/openwhisk-catalog/blob/master/packages/websocket/README.md) | Package to work with a [Web Socket](https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API) server |

<!--
TODO: place holder until we have a README for samples
| [/whisk.system/samples](https://github.com/apache/openwhisk-catalog/blob/master/packages/samples/README.md) | offers sample actions in different languages |
-->
<!--
TODO: place holder until we have a README for utils
| [/whisk.system/utils](https://github.com/apache/openwhisk-catalog/blob/master/packages/utils/README.md) | offers utilities actions such as cat, echo, and etc. |
-->
