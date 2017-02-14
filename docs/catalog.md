
# Using OpenWhisk-enabled services

In OpenWhisk, a catalog of packages gives you an easy way to enhance your app with useful capabilities, and to access external services in the ecosystem. Examples of external services that are OpenWhisk-enabled include Cloudant, The Weather Company, Slack, and GitHub.

The catalog is available as packages in the `/whisk.system` namespace. See [Browsing packages](./packages.md#browsing-packages) for information about how to browse the catalog by using the command line tool.

## Existing packages in catalog

| Package | Description |
| --- | --- |
| [/whisk.system/alarms](https://github.com/openwhisk/openwhisk-package-alarms/blob/master/README.md) | offers feed action to create periodic triggers |
| [/whisk.system/cloudant](https://github.com/openwhisk/openwhisk-package-cloudant/blob/master/README.md) | offers feed action to create database changes triggers and other convience actions to call Cloudant APIs |
| `/whisk.system/github` | offers a convenient way to use the [GitHub APIs](https://developer.github.com/). |
| `/whisk.system/samples` | offers sample actions in different languages |
| `/whisk.system/slack` | offers a convenient way to use the [Slack APIs](https://api.slack.com/). |
| `/whisk.system/utils` | offers utilities actions such as cat, echo, and etc. |
| `/whisk.system/watson-translator` | offers a convenient way to call Watson APIs to translate.|
| `/whisk.system/watson-speechToText` | offers a convenient way to call Watson APIs to convert the speech into text.|
| `/whisk.system/watson-textToSpeech` | offers a convenient way to call Watson APIs to convert the text into speech.|
| [/whisk.system/weather](https://github.com/openwhisk/openwhisk-catalog/blob/master/packages/weather/README.md) | Services from the Weather Company Data for IBM Bluemix API|


## Using the Watson packages

The Watson packages offer a convenient way to call various Watson APIs.

The following Watson packages are provided:

| Package | Description |
| --- | --- |
| `/whisk.system/watson-translator`   | Package for text translation and language identification |
| `/whisk.system/watson-textToSpeech` | Package to convert text into speech |
| `/whisk.system/watson-speechToText` | Package to convert speech into text |

**Note** The package `/whisk.system/watson` is currently deprecated, migrate to the new packages mentioned above, the new actions provide the same interface.

### Using the Watson Translator package

The `/whisk.system/watson-translator` package offers a convenient way to call Watson APIs to translate.

The package includes the following actions.

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/watson-translator` | package | username, password | Package for text translation and language identification  |
| `/whisk.system/watson-translator/translator` | action | payload, translateFrom, translateTo, translateParam, username, password | Translate text |
| `/whisk.system/watson-translator/languageId` | action | payload, username, password | Identify language |

**Note**: The package `/whisk.system/watson` is deprecated including the actions `/whisk.system/watson/translate` and `/whisk.system/watson/languageId`.

#### Setting up the Watson Translator package in Bluemix

If you're using OpenWhisk from Bluemix, OpenWhisk automatically creates package bindings for your Bluemix Watson service instances.

1. Create a Watson Translator service instance in your Bluemix [dashboard](http://console.ng.Bluemix.net).

  Be sure to remember the name of the service instance and the Bluemix organization and space you're in.

2. Make sure your OpenWhisk CLI is in the namespace corresponding to the Bluemix organization and space that you used in the previous step.

  ```
  $ wsk property set --namespace myBluemixOrg_myBluemixSpace
  ```

  Alternatively, you can use `wsk property set --namespace` to set a namespace from a list of those accessible to you.

3. Refresh the packages in your namespace. The refresh automatically creates a package binding for the Watson service instance that you created.

  ```
  $ wsk package refresh
  ```
  ```
  created bindings:
  Bluemix_Watson_Translator_Credentials-1
  ```

  ```
  $ wsk package list
  ```
  ```
  packages
  /myBluemixOrg_myBluemixSpace/Bluemix_Watson_Translator_Credentials-1 private
  ```


#### Setting up a Watson Translator package outside Bluemix

If you're not using OpenWhisk in Bluemix or if you want to set up your Watson Translator outside of Bluemix, you must manually create a package binding for your Watson Translator service. You need the Watson Translator service user name, and password.

- Create a package binding that is configured for your Watson Translator service.

  ```
  $ wsk package bind /whisk.system/watson-translator myWatsonTranslator -p username MYUSERNAME -p password MYPASSWORD
  ```


#### Translating text

The `/whisk.system/watson-translator/translator` action translates text from one language to another. The parameters are as follows:

- `username`: The Watson API user name.
- `password`: The Watson API password.
- `payload`: The text to be translated.
- `translateParam`: The input parameter indicating the text to translate. For example, if `translateParam=payload`, then the value of the `payload` parameter that is passed to the action is translated.
- `translateFrom`: A two-digit code of the source language.
- `translateTo`: A two-digit code of the target language.

- Invoke the `translator` action in your package binding to translate some text from English to French.

  ```
  $ wsk action invoke myWatsonTranslator/translator --blocking --result --param payload 'Blue skies ahead' --param translateFrom 'en' --param translateTo 'fr'
  ```

  ```
  {
      "payload": "Ciel bleu a venir"
  }
  ```


#### Identifying the language of some text

The `/whisk.system/watson-translator/languageId` action identifies the language of some text. The parameters are as follows:

- `username`: The Watson API user name.
- `password`: The Watson API password.
- `payload`: The text to identify.

- Invoke the `languageId` action in your package binding to identify the language.

  ```
  $ wsk action invoke myWatsonTranslator/languageId --blocking --result --param payload 'Ciel bleu a venir'
  ```
  ```
  {
    "payload": "Ciel bleu a venir",
    "language": "fr",
    "confidence": 0.710906
  }
  ```


### Using the Watson Text to Speech package

The `/whisk.system/watson-textToSpeech` package offers a convenient way to call Watson APIs to convert the text into speech.

The package includes the following actions.

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/watson-textToSpeech` | package | username, password | Package to convert text into speech |
| `/whisk.system/watson-textToSpeech/textToSpeech` | action | payload, voice, accept, encoding, username, password | Convert text into audio |

**Note**: The package `/whisk.system/watson` is deprecated including the action `/whisk.system/watson/textToSpeech`.

#### Setting up the Watson Text to Speech package in Bluemix

If you're using OpenWhisk from Bluemix, OpenWhisk automatically creates package bindings for your Bluemix Watson service instances.

1. Create a Watson Text to Speech service instance in your Bluemix [dashboard](http://console.ng.Bluemix.net).

  Be sure to remember the name of the service instance and the Bluemix organization and space you're in.

2. Make sure your OpenWhisk CLI is in the namespace corresponding to the Bluemix organization and space that you used in the previous step.

  ```
  $ wsk property set --namespace myBluemixOrg_myBluemixSpace
  ```

  Alternatively, you can use `wsk property set --namespace` to set a namespace from a list of those accessible to you.

3. Refresh the packages in your namespace. The refresh automatically creates a package binding for the Watson service instance that you created.

  ```
  $ wsk package refresh
  ```
  ```
  created bindings:
  Bluemix_Watson_TextToSpeech_Credentials-1
  ```

  ```
  $ wsk package list
  ```
  ```
  packages
  /myBluemixOrg_myBluemixSpace/Bluemix_Watson_TextToSpeec_Credentials-1 private
  ```


#### Setting up a Watson Text to Speech package outside Bluemix

If you're not using OpenWhisk in Bluemix or if you want to set up your Watson Text to Speech outside of Bluemix, you must manually create a package binding for your Watson Text to Speech service. You need the Watson Text to Speech service user name, and password.

- Create a package binding that is configured for your Watson Speech to Text service.

  ```
  $ wsk package bind /whisk.system/watson-speechToText myWatsonTextToSpeech -p username MYUSERNAME -p password MYPASSWORD
  ```


#### Converting some text to speech

The `/whisk.system/watson-speechToText/textToSpeech` action converts some text into an audio speech. The parameters are as follows:

- `username`: The Watson API user name.
- `password`: The Watson API password.
- `payload`: The text to convert into speech.
- `voice`: The voice of the speaker.
- `accept`: The format of the speech file.
- `encoding`: The encoding of the speech binary data.


- Invoke the `textToSpeech` action in your package binding to convert the text.

  ```
  $ wsk action invoke myWatsonTextToSpeech/textToSpeech --blocking --result --param payload 'Hey.' --param voice 'en-US_MichaelVoice' --param accept 'audio/wav' --param encoding 'base64'
  ```
  ```
  {
    "payload": "<base64 encoding of a .wav file>"
  }
  ```


### Using the Watson Speech to Text package

The `/whisk.system/watson-speechToText` package offers a convenient way to call Watson APIs to convert the speech into text.

The package includes the following actions.

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/watson-speechToText` | package | username, password | Package to convert speech into text |
| `/whisk.system/watson-speechToText/speechToText` | action | payload, content_type, encoding, username, password, continuous, inactivity_timeout, interim_results, keywords, keywords_threshold, max_alternatives, model, timestamps, watson-token, word_alternatives_threshold, word_confidence, X-Watson-Learning-Opt-Out | Convert audio into text |

**Note**: The package `/whisk.system/watson` is deprecated including the action `/whisk.system/watson/speechToText`.

#### Setting up the Watson Speech to Text package in Bluemix

If you're using OpenWhisk from Bluemix, OpenWhisk automatically creates package bindings for your Bluemix Watson service instances.

1. Create a Watson Speech to Text service instance in your Bluemix [dashboard](http://console.ng.Bluemix.net).

  Be sure to remember the name of the service instance and the Bluemix organization and space you're in.

2. Make sure your OpenWhisk CLI is in the namespace corresponding to the Bluemix organization and space that you used in the previous step.

  ```
  $ wsk property set --namespace myBluemixOrg_myBluemixSpace
  ```

  Alternatively, you can use `wsk property set --namespace` to set a namespace from a list of those accessible to you.

3. Refresh the packages in your namespace. The refresh automatically creates a package binding for the Watson service instance that you created.

  ```
  $ wsk package refresh
  ```
  ```
  created bindings:
  Bluemix_Watson_SpeechToText_Credentials-1
  ```

  ```
  $ wsk package list
  ```
  ```
  packages
  /myBluemixOrg_myBluemixSpace/Bluemix_Watson_SpeechToText_Credentials-1 private
  ```


#### Setting up a Watson Speech to Text package outside Bluemix

If you're not using OpenWhisk in Bluemix or if you want to set up your Watson Speech to Text outside of Bluemix, you must manually create a package binding for your Watson Speech to Text service. You need the Watson Speech to Text service user name, and password.

- Create a package binding that is configured for your Watson Speech to Text service.

  ```
  $ wsk package bind /whisk.system/watson-speechToText myWatsonSpeechToText -p username MYUSERNAME -p password MYPASSWORD
  ```


#### Converting speech to text

The `/whisk.system/watson-speechToText/speechToText` action converts audio speech into text. The parameters are as follows:

- `username`: The Watson API user name.
- `password`: The Watson API password.
- `payload`: The encoded speech binary data to turn into text.
- `content_type`: The MIME type of the audio.
- `encoding`: The encoding of the speech binary data.
- `continuous`: Indicates whether multiple final results that represent consecutive phrases that are separated by long pauses are returned.
- `inactivity_timeout`: The time in seconds after which, if only silence is detected in submitted audio, the connection is closed.
- `interim_results`: Indicates whether the service is to return interim results.
- `keywords`: A list of keywords to spot in the audio.
- `keywords_threshold`: A confidence value that is the lower bound for spotting a keyword.
- `max_alternatives`: The maximum number of alternative transcripts to be returned.
- `model`: The identifier of the model to be used for the recognition request.
- `timestamps`: Indicates whether time alignment is returned for each word.
- `watson-token`: Provides an authentication token for the service as an alternative to providing service credentials.
- `word_alternatives_threshold`: A confidence value that is the lower bound for identifying a hypothesis as a possible word alternative.
- `word_confidence`: Indicates whether a confidence measure in the range of 0 to 1 is to be returned for each word.
- `X-Watson-Learning-Opt-Out`: Indicates whether to opt out of data collection for the call.
 

- Invoke the `speechToText` action in your package binding to convert the encoded audio.

  ```
  $ wsk action invoke myWatsonSpeechToText/speechToText --blocking --result --param payload <base64 encoding of a .wav file> --param content_type 'audio/wav' --param encoding 'base64'
  ```
  ```
  {
    "data": "Hello Watson"
  }
  ```
 
 
## Using the Message Hub package

This package allows you to create triggers that react when messages are posted to a [Message Hub](https://developer.ibm.com/messaging/message-hub/) service instance on Bluemix.

### Creating a Trigger that listens to a Message Hub Instance
In order to create a trigger that reacts when messages are posted to a Message Hub instance, you need to use the feed named `messaging/messageHubFeed`. This feed supports the following parameters:

|Name|Type|Description|
|---|---|---|
|kafka_brokers_sasl|JSON Array of Strings|This parameter is an array of `<host>:<port>` strings which comprise the brokers in your Message Hub instance|
|user|String|Your Message Hub user name|
|password|String|Your Message Hub password|
|topic|String|The topic you would like the trigger to listen to|
|kafka_admin_url|URL String|The URL of the Message Hub admin REST interface|
|api_key|String|Your Message Hub API key|
|isJSONData|Boolean (Optional - default=false)|When set to `true` this will cause the feed to try to parse the message content as JSON before passing it along as the trigger payload.|

While this list of parameters may seem daunting, they can be automatically set for you by using the package refresh CLI command:

1. Create an instance of Message Hub service under your current organization and space that you are using for OpenWhisk.

2. Verify that the the topic you want to listen it already exists in Message Hub or create a new topic to listen for messages, like `mytopic`.

2. Refresh the packages in your namespace. The refresh automatically creates a package binding for the Message Hub service instance that you created.

  ```
  $ wsk package refresh
  ```
  ```
  created bindings:
  Bluemix_Message_Hub_Credentials-1
  ```

  ```
  $ wsk package list
  ```
  ```
  packages
  /myBluemixOrg_myBluemixSpace/Bluemix_Message_Hub_Credentials-1 private
  ```

  Your package binding now contains the credentials associated with your Message Hub instance.

3. Now all you need is to create a Trigger to be fire when new messages are posted to your Message Hub.

  ```
  $ wsk trigger create MyMessageHubTrigger -f /myBluemixOrg_myBluemixSpace/Bluemix_Message_Hub_Credentials-1/messageHubFeed -p topic mytopic
  ```

### Setting up a Message Hub package outside Bluemix

If you're not using OpenWhisk in Bluemix or if you want to set up your Message Hub outside of Bluemix, you must manually create a package binding for your Message Hub service. You need the Message Hub service credentials and connection information.

- Create a package binding that is configured for your Message Hub service.

  ```
  $ wsk trigger create MyMessageHubTrigger -f /whisk.system/messaging/messageHubFeed -p kafka_brokers_sasl "[\"kafka01-prod01.messagehub.services.us-south.bluemix.net:9093\", \"kafka02-prod01.messagehub.services.us-south.bluemix.net:9093\", \"kafka03-prod01.messagehub.services.us-south.bluemix.net:9093\"]" -p topic mytopic -p user <your Message Hub user> -p password <your Message Hub password> -p kafka_admin_url https://kafka-admin-prod01.messagehub.services.us-south.bluemix.net:443 -p api_key <your API key>
  ```

### Listening for messages to a Message Hub instance
After creating a trigger, the system will monitor the specified topic in your messaging service. When new messages are posted, the trigger will be fired.

The payload of that trigger will contain a `messages` field which is an array of messages that have been posted since the last time your trigger fired. Each message object in the array will contain the following fields:
- topic
- partition
- offset
- key
- value

In Kafka terms, these fields should be self-evident. However, the `value` requires special consideration. If the `isJSONData` parameter was set `false` (or not set at all) when the trigger was created, the `value` field will be the raw value of the posted message. However, if `isJSONData` was set to `true` when the trigger was created, the system will attempt to parse this value as a JSON object, on a best-effort basis. If parsing is successful, then the `value` in the trigger payload will be the resulting JSON object.

For example, if a message of `{"title": "Some string", "amount": 5, "isAwesome": true}` is posted with `isJSONData` set to `true`, the trigger payload might look something like this:

```
{
  "messages": [
      {
        "partition": 0,
        "key": null,
        "offset": 421760,
        "topic": "mytopic",
        "value": {
            "amount": 5,
            "isAwesome": true,
            "title": "Some string"
        }
      }
  ]
}
```
However, if the same message content is posted with `isJSONData` set to `false`, the trigger payload would look like this:

```
{
  "messages": [
    {
      "partition": 0,
      "key": null,
      "offset": 421761,
      "topic": "mytopic",
      "value": "{\"title\": \"Some string\", \"amount\": 5, \"isAwesome\": true}"
    }
  ]
}
```
### Messages are batched
You will notice that the trigger payload contains an array of messages. This means that if you are producing messages to your messaging system very quickly, the feed will attempt to batch up the posted messages into a single firing of your trigger. This allows the messages to be posted to your trigger more rapidly and efficiently.

Please keep in mind when coding actions that are fired by your trigger, that the number of messages in the payload is technically unbounded, but will always be greater than 0.


## Using the Slack package

The `/whisk.system/slack` package offers a convenient way to use the [Slack APIs](https://api.slack.com/).

The package includes the following actions:

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/slack` | package | url, channel, username | Interact with the Slack API |
| `/whisk.system/slack/post` | action | text, url, channel, username | Posts a message to a Slack channel |

Creating a package binding with the `username`, `url`, and `channel` values is suggested. With binding, you don't need to specify the values each time that you invoke the action in the package.

### Posting a message to a Slack channel

The `/whisk.system/slack/post` action posts a message to a specified Slack channel. The parameters are as follows:

- `url`: The Slack webhook URL.
- `channel`: The Slack channel to post the message to.
- `username`: The name to post the message as.
- `text`: A message to post.
- `token`: (optional) A Slack [access token](https://api.slack.com/tokens). See [below](./catalog.md#using-the-slack-token-based-api) for more detail on the use of the Slack access tokens.

The following is an example of configuring Slack, creating a package binding, and posting a message to a channel.

1. Configure a Slack [incoming webhook](https://api.slack.com/incoming-webhooks) for your team.

  After Slack is configured, you get a webhook URL that looks like `https://hooks.slack.com/services/aaaaaaaaa/bbbbbbbbb/cccccccccccccccccccccccc`. You'll need this in the next step.

2. Create a package binding with your Slack credentials, the channel that you want to post to, and the user name to post as.

  ```
  $ wsk package bind /whisk.system/slack mySlack --param url "https://hooks.slack.com/services/..." --param username Bob --param channel "#MySlackChannel"
  ```

3. Invoke the `post` action in your package binding to post a message to your Slack channel.

  ```
  $ wsk action invoke mySlack/post --blocking --result --param text "Hello from OpenWhisk!"
  ```

### Using the Slack token-based API

If you prefer, you may optionally choose to use Slack's token-based API, rather than the webhook API. If you so choose, then pass in a `token` parameter that contains your Slack [access token](https://api.slack.com/tokens). You may then use any of the [Slack API methods](https://api.slack.com/methods) as your `url` parameter. For example, to post a message, you would use a `url` parameter value of [slack.postMessage](https://api.slack.com/methods/chat.postMessage).

## Using the GitHub package

The `/whisk.system/github` package offers a convenient way to use the [GitHub APIs](https://developer.github.com/).

The package includes the following feed:

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/github` | package | username, repository, accessToken | Interact with the GitHub API |
| `/whisk.system/github/webhook` | feed | events, username, repository, accessToken | Fire trigger events on GitHub activity |

Creating a package binding with the `username`, `repository`, and `accessToken` values is suggested.  With binding, you don't need to specify the values each time that you use the feed in the package.

### Firing a trigger event with GitHub activity

The `/whisk.system/github/webhook` feed configures a service to fire a trigger when there is activity in a specified GitHub repository. The parameters are as follows:

- `username`: The user name of the GitHub repository.
- `repository`: The GitHub repository.
- `accessToken`: Your GitHub personal access token. When you [create your token](https://github.com/settings/tokens), be sure to select the repo:status and public_repo scopes. Also, make sure that you don't have any webhooks already defined for your repository.
- `events`: The [GitHub event type](https://developer.github.com/v3/activity/events/types/) of interest.

The following is an example of creating a trigger that will be fired each time that there is a new commit to a GitHub repository.

1. Generate a GitHub [personal access token](https://github.com/settings/tokens).

  The access token will be used in the next step.

2. Create a package binding that is configured for your GitHub repository and with your access token.

  ```
  $ wsk package bind /whisk.system/github myGit --param username myGitUser --param repository myGitRepo --param accessToken aaaaa1111a1a1a1a1a111111aaaaaa1111aa1a1a
  ```

3. Create a trigger for the GitHub `push` event type by using your `myGit/webhook` feed.

  ```
  $ wsk trigger create myGitTrigger --feed myGit/webhook --param events push
  ```

A commit to the GitHub repository by using a `git push` causes the trigger to be fired by the webhook. If there is a rule that matches the trigger, then the associated action will be invoked.
The action receives the GitHub webhook payload as an input parameter. Each GitHub webhook event has a similar JSON schema, but is a unique payload object that is determined by its event type.
For more information about the payload content, see the [GitHub events and payload](https://developer.github.com/v3/activity/events/types/) API documentation.


## Using the Push package

The `/whisk.system/pushnotifications` package enables you to work with a push service.

### Install the IBM Push Notification OpenWhisk package 

Download the Push package form the  [wsk-pkg-pushnotification](https://github.com/openwhisk/wsk-pkg-pushnotifications) repository.

Run the install script provided inside the package download

  ```
  $ git clone --depth=1 https://github.com/openwhisk/wsk-pkg-pushnotifications
  $ cd wsk-pkg-pushnotifications
  $ ./install.sh APIHOST AUTH WSK_CLI
  ```

  The `APIHOST` is the OpenWhisk API hostname, `AUTH` is your auth key, and `WSK_CLI` is location of the Openwhisk CLI binary.		     

The package includes the following action and feed:

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/pushnotifications` | package | appId, appSecret  | Work with the Push Service |
| `/whisk.system/pushnotifications/sendMessage` | action | text, url, deviceIds, platforms, tagNames, apnsBadge, apnsCategory, apnsActionKeyTitle, apnsSound, apnsPayload, apnsType, gcmCollapseKey, gcmDelayWhileIdle, gcmPayload, gcmPriority, gcmSound, gcmTimeToLive | Send push notification to one or more specified devices |
| `/whisk.system/pushnotifications/webhook` | feed | events | Fire trigger events on device activities (device registration, unregistration, subscription, or unsubscription) on the Push service |
Creating a package binding with the `appId` and `appSecret` values is suggested. This way, you don't need to specify these credentials every time you invoke the actions in the package.

### Creating a Push package binding

While creating a Push Notifications package binding, you must specify the following parameters,

-  `appId`: The Bluemix app GUID.
-  `appSecret`: The Bluemix push notification service appSecret.

The following is an example of creating a package binding.

1. Create a Bluemix application in [Bluemix Dashboard](http://console.ng.bluemix.net).

2. Initialize the Push Notification Service and bind the service to the Bluemix application

3. Configure the [Push Notification application](https://console.ng.bluemix.net/docs/services/mobilepush/index.html).

  Be sure to remember the `App GUID` and the `App Secret` of the Bluemix app you created.


4. Create a package binding with the `/whisk.system/pushnotifications`.

  ```
  $ wsk package bind /whisk.system/pushnotifications myPush -p appId myAppID -p appSecret myAppSecret
  ```

5. Verify that the package binding exists.

  ```
  $ wsk package list
  ```

  ```
  packages
  /myNamespace/myPush private binding
  ```

### Sending push notifications

The `/whisk.system/pushnotifications/sendMessage` action sends push notifications to registered devices. The parameters are as follows:
- `text`: The notification message to be shown to the user. For example: `-p text "Hi ,OpenWhisk send a notification"`.
- `url`: An optional URL that can be sent along with the alert. For example: `-p url "https:\\www.w3.ibm.com"`.
- `deviceIds` The list of specified devices. For example: `-p deviceIds "[\"deviceID1\"]"`.
- `platforms` Send notification to the devices of the specified platforms. 'A' for apple (iOS) devices and 'G' for google (Android) devices. For example `-p platforms "[\"A\"]"`.
- `tagNames` Send notification to the devices that have subscribed to any of these tags. For example `-p tagNames "[\"tag1\"]" `.
- `gcmPayload`: Custom JSON payload that will be sent as part of the notification message. For example: `-p gcmPayload "{\"hi\":\"hello\"}"`
- `gcmSound`: The sound file (on device) that will be attempted to play when the notification arrives on the device.
- `gcmCollapseKey`: This parameter identifies a group of messages
- `gcmDelayWhileIdle`: When this parameter is set to true, it indicates that the message will not be sent until the device becomes active.
- `gcmPriority`: Sets the priority of the message.
- `gcmTimeToLive`: This parameter specifies how long (in seconds) the message will be kept in GCM storage if the device is offline.
- `apnsBadge`: The number to display as the badge of the application icon.
- `apnsCategory`: The category identifier to be used for the interactive push notifications.
- `apnsIosActionKey`: The title for the Action key .
- `apnsPayload`: Custom JSON payload that will be sent as part of the notification message.
- `apnsType`: ['DEFAULT', 'MIXED', 'SILENT'].
- `apnsSound`: The name of the sound file in the application bundle. The sound of this file is played as an alert.

Here is an example of sending push notification from the pushnotification package.

1. Send push notification by using the `sendMessage` action in the package binding that you created previously. Be sure to replace `/myNamespace/myPush` with your package name.

  ```
  $ wsk action invoke /myNamespace/myPush/sendMessage --blocking --result  -p url https://example.com -p text "this is my message"  -p sound soundFileName -p deviceIds "[\"T1\",\"T2\"]"
  ```

  ```
  {
  "result": {
  "pushResponse": "{"messageId":"11111H","message":{"message":{"alert":"this is my message","url":"http.google.com"},"settings":{"apns":{"sound":"default"},"gcm":{"sound":"default"},"target":{"deviceIds":["T1","T2"]}}}"
  },
  "status": "success",
  "success": true
  }
  ```

### Firing a trigger event on Push activity

The `/whisk.system/pushnotifications/webhook` configures the Push service to fire a trigger when there is a device activity such as device registration / unregistration or subscription / unsubscription in a specified application

The parameters are as follows:

- `appId:` The Bluemix app GUID.
- `appSecret:` The Bluemix push notification service appSecret.
- `events:` Supported events are `onDeviceRegister`, `onDeviceUnregister`, `onDeviceUpdate`, `onSubscribe`, `onUnsubscribe`.To get notified for all events use the wildcard character `*`.

The following is an example of creating a trigger that will be fired each time there is a new device registered with the Push Notifications service application.

1. Create a package binding configured for your Push Notifications service with your appId and appSecret.

  ```
  $ wsk package bind /whisk.system/pushnotifications myNewDeviceFeed --param appID myapp --param appSecret myAppSecret --param events onDeviceRegister
  ```

2. Create a trigger for the Push Notifications service `onDeviceRegister` event type using your `myPush/webhook` feed.

  ```
  $ wsk trigger create myPushTrigger --feed myPush/webhook --param events onDeviceRegister
  ```
