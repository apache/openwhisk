
# Using OpenWhisk-enabled services

In OpenWhisk, a catalog of packages gives you an easy way to enhance your app with useful capabilities, and to access external services in the ecosystem. Examples of external services that are OpenWhisk-enabled include Cloudant, The Weather Company, Slack, and GitHub.

The catalog is available as packages in the `/whisk.system` namespace. See [Browsing packages](./packages.md#browsing-packages) for information about how to browse the catalog by using the command line tool.

## Existing packages in catalog

| Package | Description |
| --- | --- |
| [/whisk.system/alarms](https://github.com/openwhisk/openwhisk-package-alarms/blob/master/README.md) | Package to create periodic triggers |
| [/whisk.system/cloudant](https://github.com/openwhisk/openwhisk-package-cloudant/blob/master/README.md) | Package to create database change triggers and other convenience actions to call Cloudant APIs |
| [/whisk.system/github](https://github.com/openwhisk/openwhisk-package-catalog/blob/master/packages/github/README.md) | Package to create webhook triggers for [GitHub](https://developer.github.com/). |
| [/whisk.system/messaging](https://github.com/openwhisk/openwhisk-package-kafka/blob/master/README.md) | Package to create triggers that react when messages are posted to a Message Hub and an action to produce messages  |
| [/whisk.system/pushnotifications](https://github.com/openwhisk/openwhisk-package-pushnotifications/blob/master/README.md) | Package to create triggers for the Push Notification service and an action to send push messages  |
| [/whisk.system/slack](https://github.com/openwhisk/openwhisk-catalog/blob/master/packages/slack/README.md) | Package to post to the [Slack APIs](https://api.slack.com/). |
| [/whisk.system/watson-translator](https://github.com/openwhisk/openwhisk-catalog/blob/master/packages/watson-translator/README.md) | Package for text translation and language identification |
| [/whisk.system/watson-speechToText](https://github.com/openwhisk/openwhisk-catalog/blob/master/packages/watson-speechToText/README.md) | Package to convert speech into text |
| [/whisk.system/watson-textToSpeech](https://github.com/openwhisk/openwhisk-catalog/blob/master/packages/watson-textToSpeech/README.md) | Package to convert text into speech |
| [/whisk.system/weather](https://github.com/openwhisk/openwhisk-catalog/blob/master/packages/weather/README.md) | Package to get data from the Weather Company Data for IBM Bluemix API|

<!--
TODO: place holder until we have a README for samples 
| [/whisk.system/samples](https://github.com/openwhisk/openwhisk-catalog/blob/master/packages/samples/README.md) | offers sample actions in different languages |
-->
<!--
TODO: place holder until we have a README for utils
| [/whisk.system/utils](https://github.com/openwhisk/openwhisk-catalog/blob/master/packages/utils/README.md) | offers utilities actions such as cat, echo, and etc. |
-->
