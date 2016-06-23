
# Using OpenWhisk-enabled services 

In OpenWhisk, a catalog of packages gives you an easy way to enhance your app with useful capabilities, and to access external services in the ecosystem. Examples of external services that are OpenWhisk-enabled include Cloudant, The Weather Company, Slack, and GitHub.

The catalog is available as packages in the `/whisk.system` namespace. See [Browsing packages](./packages.md#browsing-packages) for information about how to browse the catalog by using the command line tool.

The topics that follow document some of the packages in the catalog.

## Using the Cloudant package
The `/whisk.system/cloudant` package enables you to work with a Cloudant database. It includes the following actions and feeds.

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/cloudant` | package | BluemixServiceName, host, username, password, dbname, includeDoc, overwrite | Work with a Cloudant database |
| `/whisk.system/cloudant/read` | action | dbname, includeDoc, id | Read a document from a database |
| `/whisk.system/cloudant/write` | action | dbname, overwrite, doc | Write a document to a database |
| `/whisk.system/cloudant/changes` | feed | dbname, includeDoc, maxTriggers | Fire trigger events on changes to a database |

The following topics walk through setting up a Cloudant database, configuring an associated package, and using the actions and feeds in the `/whisk.system/cloudant` package.

### Setting up a Cloudant database in Bluemix

If you're using OpenWhisk from Bluemix, OpenWhisk automatically creates package bindings for your Bluemix Cloudant service instances. If you're not using OpenWhisk and Cloudant from Bluemix, skip to the next step.

1. Create a Cloudant service instance in your Bluemix [dashboard](http://console.ng.Bluemix.net).

  Be sure to remember the name of the service instance and the Bluemix organization and space you're in.

2. Make sure your OpenWhisk CLI is in the namespace corresponding to the Bluemix organization and space that you used in the previous step.

  ```
  $ wsk property set --namespace myBluemixOrg_myBluemixSpace
  ```

  Alternatively, you can use `wsk property set --namespace` to set a namespace from a list of those accessible to you.

3. Refresh the packages in your namespace. The refresh automatically creates a package binding for the Cloudant service instance that you created.

  ```
  $ wsk package refresh
  ```
  ```
  created bindings:
  Bluemix_testCloudant_Credentials-1
  ```

  ```
  $ wsk package list
  ```
  ```
  packages
  /myBluemixOrg_myBluemixSpace/Bluemix_testCloudant_Credentials-1 private binding
  ```

  You should see the fully qualified name of the package binding that corresponds to your Bluemix Cloudant service instance.

4. Check to see that the package binding created previously is configured with your Cloudant Bluemix service instance host and credentials.

  ```
  $ wsk package get /myBluemixOrg_myBluemixSpace/Bluemix_testCloudant_Credentials-1
  ```
  ```
  ok: got package /myBluemixOrg_myBluemixSpace/Bluemix_testCloudant_Credentials-1, projecting parameters
  [
      ...
      {
          "key": "username",
          "value": "cdb18832-2bbb-4df2-b7b1-Bluemix"
      },
      {
          "key": "host",
          "value": "cdb18832-2bbb-4df2-b7b1-Bluemix.cloudant.com"
      },
      {
          "key": "password",
          "value": "c9088667484a9ac827daf8884972737"
      }
      ...
  ]
  ```

### Setting up a Cloudant database outside Bluemix

If you're not using OpenWhisk in Bluemix or if you want to set up your Cloudant database outside of Bluemix, you must manually create a package binding for your Cloudant account. You need the Cloudant account hostname, user name, and password.

1. Create a package binding configured for your Cloudant account.

  ```
  $ wsk package bind /whisk.system/cloudant myCloudant -p username 'MYUSERNAME' -p password 'MYPASSWORD' -p host 'MYCLOUDANTACCOUNT.cloudant.com'
  ```

2. Verify that the package binding exists.

  ```
  $ wsk package list
  ```
  ```
  packages
  /myNamespace/myCloudant private binding
  ```


### Listening for changes to a Cloudant database

You can use the `changes` feed to configure a service to fire a trigger on every change to your Cloudant database. The parameters are as follows:

- `dbname`: Name of Cloudant database.
- `includeDoc`: If set to true, each trigger event that is fired includes the modified Cloudant document. 
- `maxTriggers`: Stop firing triggers when this limit is reached. Defaults to 1000. You can set it to maximum 10,000. If you try to set more than 10,000, the request is rejected.

1. Create a trigger with the `changes` feed in the package binding that you created previously. Be sure to replace `/myNamespace/myCloudant` with your package name.

  ```
  $ wsk trigger create myCloudantTrigger --feed /myNamespace/myCloudant/changes --param dbname testdb --param includeDoc true
  ```
  ```
  ok: created trigger feed myCloudantTrigger
  ```

2. Poll for activations.

  ```
  $ wsk activation poll
  ```

3. In your Cloudant dashboard, either modify an existing document or create a new one.

4. Observe new activations for the `myCloudantTrigger` trigger for each document change.

**Note**: If you are unable to observe new activations, see the subsequent sections on reading from and writing to a Cloudant database. Testing the reading and writing steps below will help verify that your Cloudant credentials are correct.

You can now create rules and associate them to actions to react to the document updates.

The content of the generated events depends on the value of the `includeDoc` parameter when creating the trigger. If set to true, each trigger event that is fired includes the modified Cloudant document. For example, consider the following modified document:

  ```
  {
    "_id": "6ca436c44074c4c2aa6a40c9a188b348",
    "_rev": "3-bc4960fc13aa368afca8c8427a1c18a8",
    "name": "Heisenberg"
  }
  ```

The code in this example generates a trigger event with the corresponding `_id`, `_rev` and `name` parameters. In fact, the JSON representation of the trigger event is identical to the document.

Otherwise, if `includeDoc` is false, the events include the following parameters:

- `id`: The document ID.
- `seq`: The sequence identifier generated by Cloudant.
- `changes`: An array of objects, each of which has a `rev` field that contains the revision ID of the document.

The JSON representation of the trigger event is as follows:

  ```
  {
      "id": "6ca436c44074c4c2aa6a40c9a188b348",
      "seq": "2-g1AAAAL9aJyV-GJCaEuqx4-BktQkYp_dmIfC",
      "changes": [
          {
              "rev": "2-da3f80848a480379486fb4a2ad98fa16"
          }
      ]
  }
  ```

### Writing to a Cloudant database

You can use an action to store a document in a Cloudant database called `testdb`. Make sure that this database exists in your Cloudant account.

1. Store a document by using the `write` action in the package binding you created previously. Be sure to replace `/myNamespace/myCloudant` with your package name.

  ```
  $ wsk action invoke /myNamespace/myCoudant/write --blocking --result --param dbname testdb --param doc '{"_id":"heisenberg", "name":"Walter White"}'
  ```
  ```
  ok: invoked /myNamespace/myCoudant/write with id 62bf696b38464fd1bcaff216a68b8287
  {
    "id": "heisenberg",
    "ok": true,
    "rev": "1-9a94fb93abc88d8863781a248f63c8c3"
  }
  ```

2. Verify that the document exists by browsing for it in your Cloudant dashboard.

  The dashboard URL for the `testdb` database looks something like the following: `https://MYCLOUDANTACCOUNT.cloudant.com/dashboard.html#database/testdb/_all_docs?limit=100`.


### Reading from a Cloudant database

You can use an action to fetch a document from a Cloudant database called `testdb`. Make sure that this database exists in your Cloudant account.

1. Fetch a document by using the `read` action in the package binding that you created previously. Be sure to replace `/myNamespace/myCloudant` with your package name.

  ```
  $ wsk action invoke /myNamespace/myCoudant/read --blocking --result --param dbname testdb --param id heisenberg
  ```
  ```
  {
    "_id": "heisenberg",
    "_rev": "1-9a94fb93abc88d8863781a248f63c8c3"
    "name": "Walter White"
  }
  ```


## Using the Alarm package

The `/whisk.system/alarms` package can be used to fire a triggers at a specified frequency. This is useful to setup recurring jobs or tasks, such as invoking a system backup action every hour.

The package includes the following feed.

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/alarms` | package | - | Alarms and periodic utility |
| `/whisk.system/alarms/alarm` | feed | cron, trigger_payload, maxTriggers | Fire trigger event periodically |


### Firing a trigger event periodically

The `/whisk.system/alarms/alarm` feed configures the Alarm service to fire a trigger event at a specified frequency. The parameters are as follows:

- `cron`: A string, based on the Unix crontab syntax, that indicates when to fire the trigger in Coordinated Universal Time (UTC). The string is a sequence of six fields separated by spaces: `X X X X X X `. For more details on using cron syntax, see: https://github.com/ncb000gt/node-cron. Here are some examples of the frequency indicated by the string:

  - `* * * * * *`: every second.
  - `0 * * * * *`: top of every minute.
  - `* 0 * * * *`: top of every hour.
  - `0 0 9 8 * *`: at 9:00:00AM (UTC) on the eighth day of every month

- `trigger_payload`: The value of this parameter becomes the content of the trigger every time the trigger is fired.

- `maxTriggers`: Stop firing triggers when this limit is reached. Defaults to 1000. You can set it to maximum 10,000. If you try to set more than 10,000, the request is rejected.

Here is an example of creating a trigger that will be fired once every 20 seconds with `name` and `place` values in the trigger event.

  ```
  $ wsk trigger create periodic --feed /whisk.system/alarms/alarm --param cron '*/20 * * * * *' --param trigger_payload '{"name":"Odin","place":"Asgard"}'
  ```

Each generated event will include as parameters the properties specified in the `trigger_payload` value. In this case, each trigger event will have parameters `name=Odin` and `place=Asgard`.


## Using the Weather package

The `/whisk.system/weather` package offers a convenient way to call the IBM Weather Insights API.

The package includes the following action.

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/weather` | package | apiKey | Services from IBM Weather Insights API  |
| `/whisk.system/weather/forecast` | action | apiKey, latitude, longitude, timePeriod | forecast for specified time period|

While not required, it's suggested that you create a package binding with the `apiKey` value. This way you don't need to specify the key every time you invoke the actions in the package.

### Getting a weather forecast for a location

The `/whisk.system/weather/forecast` action returns a weather forecast for a location by calling an API from The Weather Company. The parameters are as follows:

- `apiKey`: An API key for The Weather Company that is entitled to invoke the forecast API.
- `latitude`: The latitude coordinate of the location.
- `longitude`: The longitude coordinate of the location.
- `timeperiod`: Time period for the forecast. Valid options are '10day' - (default) Returns a daily 10-day forecast , '24hour' - Returns an hourly 2-day forecast, , 'current' - Returns the current weather conditions, 'timeseries' - Returns both the current observations and up to 24 hours of past observations, from the current date and time. 


Here is an example of creating a package binding and then getting a 10-day forecast.

1. Create a package binding with your API key.

  ```
  $ wsk package bind /whisk.system/weather myWeather --param apiKey 'MY_WEATHER_API'
  ```

2. Invoke the `forecast` action in your package binding to get the weather forecast.

  ```
  $ wsk action invoke myWeather/forecast --blocking --result --param latitude '43.7' --param longitude '-79.4'
  ```

  ```
  {
      "forecasts": [
          {
              "dow": "Wednesday",
              "max_temp": -1,
              "min_temp": -16,
              "narrative": "Chance of a few snow showers. Highs -2 to 0C and lows -17 to -15C.",
              ...
          },
          {
              "class": "fod_long_range_daily",
              "dow": "Thursday",
              "max_temp": -4,
              "min_temp": -8,
              "narrative": "Mostly sunny. Highs -5 to -3C and lows -9 to -7C.",
              ...
          },
          ...
      ],
  }
  ```


## Using the Watson package

The `/whisk.system/watson` package offers a convenient way to call various Watson APIs.

The package includes the following actions.

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/watson` | package | username, password | Actions for the Watson analytics APIs |
| `/whisk.system/watson/translate` | action | translateFrom, translateTo, translateParam, username, password | Translate text |
| `/whisk.system/watson/languageId` | action | payload, username, password | Identify language |
| `/whisk.system/watson/speechToText` | action | payload, content_type, encoding, username, password, continuous, inactivity_timeout, interim_results, keywords, keywords_threshold, max_alternatives, model, timestamps, watson-token, word_alternatives_threshold, word_confidence, X-Watson-Learning-Opt-Out | Convert audio into text |
| `/whisk.system/watson/textToSpeech` | action | payload, voice, accept, encoding, username, password | Convert text into audio |

While not required, it's suggested that you create a package binding with the `username` and `password` values. This way you don't need to specify these credentials every time you invoke the actions in the package.

### Translating text

The `/whisk.system/watson/translate` action translate text from one language to another. The parameters are as follows:

- `username`: The Watson API username.
- `password`: The Watson API password.
- `translateParam`: The input parameter to translate. For example, if `translateParam=payload`, then the value of the `payload` parameter passed to the action is translated.
- `translateFrom`: A two digit code of the source language.
- `translateTo`: A two digit code of the target language.

The following is an example of creating a package binding and translating some text.

1. Create a package binding with your Watson credentials.

  ```
  $ wsk package bind /whisk.system/watson myWatson --param username 'MY_WATSON_USERNAME' --param password 'MY_WATSON_PASSWORD'
  ```

2. Invoke the `translate` action in your package binding to translate some text from English to French.

  ```
  $ wsk action invoke myWatson/translate --blocking --result --param payload 'Blue skies ahead' --param translateParam 'payload' --param translateFrom 'en' --param translateTo 'fr'
  ```

  ```
  {
      "payload": "Ciel bleu a venir"
  }
  ```


### Identifying the language of some text

The `/whisk.system/watson/languageId` action identifies the language of some text. The parameters are as follows:

- `username`: The Watson API username.
- `password`: The Watson API password.
- `payload`: The text to identify.

Here is an example of creating a package binding and identifying the language of some text.

1. Create a package binding with your Watson credentials.

  ```
  $ wsk package bind /whisk.system/watson myWatson -p username 'MY_WATSON_USERNAME' -p password 'MY_WATSON_PASSWORD'
  ```

2. Invoke the `languageId` action in your package binding to identify the language.

  ```
  $ wsk action invoke myWatson/languageId --blocking --result --param payload 'Ciel bleu a venir'
  ```
  ```
  {
    "payload": "Ciel bleu a venir",
    "language": "fr",
    "confidence": 0.710906
  }
  ```


### Converting some text to speech

The `/whisk.system/watson/textToSpeech` action converts some text into an audio speech. The parameters are as follows:

- `username`: The Watson API username.
- `password`: The Watson API password.
- `payload`: The text to convert into speech.
- `voice`: The voice of the speaker.
- `accept`: The format of the speech file.
- `encoding`: The encoding of the speech binary data.

Here is an example of creating a package binding and converting some text to speech.

1. Create a package binding with your Watson credentials.

  ```
  $ wsk package bind /whisk.system/watson myWatson -p username 'MY_WATSON_USERNAME' -p password 'MY_WATSON_PASSWORD'
  ```

2. Invoke the `textToSpeech` action in your package binding to convert the text.

  ```
  $ wsk action invoke myWatson/textToSpeech --blocking --result --param payload 'Hey.' --param voice 'en-US_MichaelVoice' --param accept 'audio/wav' --param encoding 'base64'
  ```
  ```
  {
    "payload": "<base64 encoding of a .wav file>"
  }
  ```


### Converting speech to text

The `/whisk.system/watson/speechToText` action converts audio speech into text. The parameters are as follows:

- `username`: The Watson API username.
- `password`: The Watson API password.
- `payload`: The encoded speech binary data to turn into text.
- `content_type`: The MIME type of the audio.
- `encoding`: The encoding of the speech binary data.
- `continuous`: Indicates whether multiple final results that represent consecutive phrases separated by long pauses are returned.
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
 
Here is an example of creating a package binding and converting speech to text.

1. Create a package binding with your Watson credentials.

  ```
  $ wsk package bind /whisk.system/watson myWatson -p username 'MY_WATSON_USERNAME' -p password 'MY_WATSON_PASSWORD'
  ```

2. Invoke the `speechToText` action in your package binding to convert the encoded audio.

  ```
  $ wsk action invoke myWatson/speechToText --blocking --result --param payload <base64 encoding of a .wav file> --param content_type 'audio/wav' --param encoding 'base64'
  ```
  ```
  {
    "data": "Hello Watson"
  }
  ```
  
  
## Using the Slack package

The `/whisk.system/slack` package offers a convenient way to use the [Slack APIs](https://api.slack.com/).

The package includes the following actions:

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/slack` | package | url, channel, username | Interact with the Slack API |
| `/whisk.system/slack/post` | action | text, url, channel, username | Posts a message to a Slack channel |

While not required, it's suggested that you create a package binding with the `username`, `url`, and 'channel' values. With binding, you don't need to specify the values each time that you invoke the action in the package.

### Posting a message to a Slack channel

The `/whisk.system/slack/post` action posts a message to a specified Slack channel. The parameters are as follows:

- `url`: The Slack webhook URL.
- `channel`: The Slack channel to post the message to.
- `username`: The name to post the message as.
- `text`: A message to post.

The following is an example of configuring Slack, creating a package binding, and posting a message to a channel.

1. Configure a Slack [incoming webhook](https://api.slack.com/incoming-webhooks) for your team.

  After Slack is configured, you should get a Webhook URL that looks like `https://hooks.slack.com/services/aaaaaaaaa/bbbbbbbbb/cccccccccccccccccccccccc`. You'll need this in the next step.

2. Create a package binding with your Slack credentials, the channel that you want to post to, and the user name to post as.

  ```
  $ wsk package bind /whisk.system/slack mySlack --param url 'https://hooks.slack.com/services/...' --param username 'Bob' --param channel '#MySlackChannel'
  ```

3. Invoke the `post` action in your package binding to post a message to your Slack channel.

  ```
  $ wsk action invoke mySlack/post --blocking --result --param text 'Hello from OpenWhisk!'
  ```


## Using the GitHub package

The `/whisk.system/github` package offers a convenient way to use the [GitHub APIs](https://developer.github.com/).

The package includes the following feed:

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/github` | package | username, repository, accessToken | Interact with the GitHub API |
| `/whisk.system/github/webhook` | feed | events, username, repository, accessToken | Fire trigger events on GitHub activity |

While not required, it's suggested that you create a package binding with the `username`, `repository`, and `accessToken` values.  With binding, you don't need to specify the values each time that you use the feed in the package.

### Firing a trigger event with GitHub activity

The `/whisk.system/github/webhook` feed configures a service to fire a trigger when there is activity in a specified GitHub repository. The parameters are as follows:

- `username`: The username of the GitHub repository.
- `repository`: The GitHub repository.
- `accessToken`: Your GitHub personal access token. When you [create your token](https://github.com/settings/tokens), be sure to select the repo:status and public_repo scopes. Also, make sure you don't have any Webhooks already defined for your repository.
- `events`: The [GitHub event type](https://developer.github.com/v3/activity/events/types/) of interest.

The following is an example of creating a trigger that will be fired each time that there is a new commit to a GitHub repository.

1. Generate a GitHub [personal access token](https://github.com/settings/tokens).

   The access token will be used in the next step.

2. Create a package binding configured for your GitHub respository and with your accesss token.

  ```
  $ wsk package bind /whisk.system/github myGit --param username myGitUser --param repository myGitRepo --param accessToken aaaaa1111a1a1a1a1a111111aaaaaa1111aa1a1a
  ```

3. Create a trigger for the GitHub `push` event type using your `myGit/webhook` feed.

  ```
  $ wsk trigger create myGitTrigger --feed myGit/webhook --param events push
  ```

A commit to the Github repository via a `git push` will cause the trigger to be fired by the webhook. If there is a rule that matches the trigger, then the associated action will be invoked.
The action receives the Github webhook payload as an input parameter. Each Github webhook event has a similar JSON schema, but a unique payload object that is determined by its event type.
For more information on the payload content see the [Github events and payload](https://developer.github.com/v3/activity/events/types/) API documentation.


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


The package includes the following feed:

| Entity | Type | Parameters | Description |
| --- | --- | --- | --- |
| `/whisk.system/pushnotifications` | package | appId, appSecret  | Work with the Push Service |
| `/whisk.system/pushnotifications/sendMessage` | action | text, url, deviceIds, platforms, tagNames, apnsBadge, apnsCategory, apnsActionKeyTitle, apnsSound, apnsPayload, apnsType, gcmCollapseKey, gcmDelayWhileIdle, gcmPayload, gcmPriority, gcmSound, gcmTimeToLive | Send push notification to the specified device(s) |
| `/whisk.system/pushnotifications/webhook` | feed | events | Fire trigger events on device activities (device (un)registration / (un)subscription) on the Push Service |
Even though its not mandatory , it's suggested that you create a package binding with the `appId` and `appSecret` values. This way you don't need to specify these credentials every time you invoke the actions in the package.

### Setting up IBM Push Notifications package

While creating a  IBM Push Notifications package you have to give the following parameters,

-  `appId`: The Bluemix app GUID.
-  `appSecret`: The Bluemix push notification service appSecret.

The following is an example of creating a package binding.

1. Create a Bluemix application in [Bluemix Dashboard](http://console.ng.bluemix.net).

2. Initialize the Push Notification Service and bind the service to the Bluemix application

3. Configure the [IBM Push Notification application](https://console.ng.bluemix.net/docs/services/mobilepush/index.html).

  Be sure to remember the `App GUID`  and the `App Secret` of the Bluemix app you created.


4. Create a package binding with the `/whisk.system/pushnotifications`.

  ```
  $ wsk package bind /whisk.system/pushnotifications myPush -p appId "myAppID" -p appSecret "myAppSecret"
  ```

5. Verify that the package binding exists.

  ```
  $ wsk package list
  ```

  ```
  packages
  /myNamespace/myPush private binding
  ```

### Sending Push Notifications

The `/whisk.system/pushnotifications/sendMessage` action sends push notifications to registered devices. The parameters are as follows:
- `text` - The notification message to be shown to the user. Eg: -p text "Hi ,OpenWhisk send a notification".
- `url`: An optional URL that can be sent along with the alert. Eg : -p url "https:\\www.w3.ibm.com".
- `gcmPayload` - Custom JSON payload that will be sent as part of the notification message. Eg: -p gcmPayload "{"hi":"hello"}"
- `gcmSound` - The sound file (on device) that will be attempted to play when the notification arrives on the device .
- `gcmCollapseKey` - This parameter identifies a group of messages
- `gcmDelayWhileIdle` - When this parameter is set to true, it indicates that the message should not be sent until the device becomes active.
- `gcmPriority` - Sets the priority of the message.
- `gcmTimeToLive` - This parameter specifies how long (in seconds) the message should be kept in GCM storage if the device is offline.
- `apnsBadge` - The number to display as the badge of the application icon.
- `apnsCategory` -  The category identifier to be used for the interactive push notifications .
- `apnsIosActionKey` - The title for the Action key .
- `apnsPayload` - Custom JSON payload that will be sent as part of the notification message.
- `apnsType` - ['DEFAULT', 'MIXED', 'SILENT'].
- `apnsSound` - The name of the sound file in the application bundle. The sound of this file is played as an alert.

Here is an example of sending push notification from the pushnotification package.

1. Send push notification by using the `sendMessage` action in the package binding that you created previously. Be sure to replace `/myNamespace/myPush` with your package name.

  ```
  $ wsk action invoke /myNamespace/myPush/sendMessage --blocking --result  -p url https://example.com -p text "this is my message"  -p sound soundFileName -p deviceIds '["T1","T2"]'
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

### Firing a trigger event on IBM Push Notifications Service activity

The `/whisk.system/pushnotifications/webhook` configures the IBM Push Notifications service to fire a trigger when there is a device activity such as device registration / unregistration or subscription / unsubscription in a specified application

The parameters are as follows:

- `appId:` The Bluemix push notification service appSecret.
- `appSecret:` The Bluemix app GUID.
- `events:` Supported events are `onDeviceRegister`, `onDeviceUnregister`, `onDeviceUpdate`, `onSubscribe`, `onUnsubscribe`.To get notified for all events use the wildcard character `*`.

The following is an example of creating a trigger that will be fired each time there is a new device registered with the IBM Push Notifications Service application.

1. Create a package binding configured for your IBM Push Notifications service with your appId and appSecret.

  ```
  $ wsk package bind /whisk.system/pushnotifications myNewDeviceFeed --param appID myapp --param appSecret myAppSecret --param events onDeviceRegister
  ```

2. Create a trigger for the IBM Push Notifications Service `onDeviceRegister` event type using your `myPush/webhook` feed.

  ```
  $ wsk trigger create myPushTrigger --feed myPush/webhook --param events onDeviceRegister
  ```
