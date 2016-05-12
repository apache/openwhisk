# Git-to-Slack Tutorial

In this lab you will leverage Whisk in order to create a simple but useful bot for the messaging platform Slack.
The application composed of Whisk actions will be waiting for events on the code collaboration platform Github.
When a change is made to the code, the application will process the information about the change and publish them to a channel on Slack.

## Prerequisites

You'll need the following to complete this lab:

- A Mac with an editor installed.
- Your Whisk credentials.
- An installation of the Whisk CLI tool.
- Access to a Slack team
- A valid Watson language translation API username and password.
- Credentials for Github and Slack

### Get Github access token

1. Log in to Github account using the provided credentials
2. Go to `Settings` and select `Personal access tokens`.
3. Click on `Generate new token`.
4. Enter your password.
5. Select the following scopes: `repo`, `public_repo`, `user` and `admin:repo_hook` and click `Generate token`
6. Copy and save your token. Please be aware that you won't be able to see it again.

### Log in for your Slack team

1. Log in to your Slack team with your credentials
2. Create a new private channel using the button next to `Channels` in the sidebar.

## 2. Creating a package binding & invoking a whisk action

A Whisk action is a small, stateless piece of code which runs on the Whisk platform. We can invoke an action using the Whisk Command Line Interface (CLI) which is accessible from the command line. `wsk` lists the available commands.

Whisk provides a number of packages which enable integration with a number of services.
Our first action will post a text message to Slack using the `slack` package available in Whisk.
It takes 4 arguments as an input:

* username: The name the bot will have when it posts into a channel on Slack
* channel: The channel we want so post a message in
* url: An incoming WebHook URL which is provided by Slack
* text: The text we want to post

We want to invoke our action regularly and don't want to pass the parameters for `url` , `channel` and `username` every time when the only thing that changes is the text. That's why we can create a package binding, basically our "own" version of the slack package which already pre-populates these values for us.
Execute the following command with your values.

`wsk package bind /whisk.system/slack mySlack -p username Octocat -p channel $CHANNELNAME -p url $SLACKURL`

This command creates a package binding called `mySlack` and enables us to post to the Slack channel in a very simple way:

`wsk action invoke mySlack/post -p text "Hello whisk!"`


After a few seconds you should see "Hello whisk!" in your Slack channel. Congratulations! The package `slack` contains the action `simpleChannelMessage` to post to a channel and only requires us to configure the parameters. We can set `username`,`channel` and `text` to whatever we want. These parameters can be passed to the action using the `-p` flag.



## 3. Invoke an action from another

We want to invoke actions not only from the command line but automatically. One possibility to invoke a action is to call it directly from the code of another action.

```
function main(params){

  var parameters = {
    "text": "Another action invoked the action to write to slack!"
  };

  whisk.invoke({
    name: "mySlack/post",
    parameters: parameters,
    blocking: true,
    next: function(error, activation) {
      if (error) {
        whisk.error();
      } else {
        // Do something else after the message has been sent to Slack
        whisk.done();
      }
    }
  });

  return whisk.async();
}
```

As you can see, our new action is just some JavaScript code. The invoke method requires the action to call. In our case it is `post` in the package binding `mySlack`. The second parameter is an object with parameters that are required in the called action. We put our `text` for the Slack message into this object. The third parameter is a valid authentication key and the fourth parameter is a callback function.
To add this action to Whisk we save this file as `gitToSlack1.js` and add it to Whisk with the following command:

`wsk action create gitToSlack1 /path/to/gitToSlack1.js`

Afterwards this action can be invoked, too.

`wsk action invoke gitToSlack1`

## 4. Binding of the git package

To get information about changes on Github we can use the `github` package.
As some information are required we also create an own binding of this package.
Here, the Github `username`, a `repository` and an `accessToken` are required.

`wsk package bind /whisk.system/github myGit -p username $USERNAME -p repository $REPOSITORY -p accessToken $ACCESSTOKEN`

This will create a package binding called `myGit` with our information in order to set up a webhook on the repository which will deliver information about changes to whisk.

## 5. Creating a trigger to listen on Github changes

Triggers are a named channel for a stream of events. A trigger can be configured with a feed that sets up the source of events, in our case Github. The action `webhook` is part of the `github` package and takes a comma-separated list of events as an input. In our case, we only want to know when people push changes to the repository.

`wsk trigger create myGitTrigger --feed myGit/webhook -p events push`

The new trigger is called `myGitTrigger`. On creating the trigger we execute the feed `webhook`. It creates an Webhook in Github that calls the trigger when someone performs a `push` to the repository.

Now all pushes on Github will fire our newly created trigger.

## 6. Create a rule to handle fired triggers

The last step is to combine the incoming event from Github and the post of the Slack-message.

Therefore we create a action `gitToSlack2`.

```
function main(params){
  var commits = params.commits;

  var slackPost = {
    username: "Octocat",
    text: params.sender.login + " pushed " + commits.length +" commits to " + params.repository.full_name,
    icon_emoji: ":github_icon:"
  };

  slackPost.attachments = [];

  for(var i = 0; i < commits.length; i++){
    slackPost.attachments.push(
    {
      "color": "#36a64f",
      "author_name": commits[i].author.username,
      "title": commits[i].id,
      "title_link": commits[i].url,
      "text": commits[i].timestamp,
      "thumb_url": params.sender.avatar_url,
      "fields": [
        {
          "title": "English",
          "value": commits[i].message,
          "short": true
        }
      ]
    });
  }

  whisk.invoke({
    name: "mySlack/post",
    parameters: slackPost,
    blocking: true,
    next: function(error, activation) {
      whisk.done(undefined, error);
    }
  });

  return whisk.async();
}

```

`wsk action create gitToSlack2 /path/to/gitToSlack2.js`

The variable `params` is the body of the request, sent by Github. In this object are some information about the push. For example all commits. They will be added to the Slack message as attachments. A slack message can have multiple attachments so it makes sense for our little application to have one attachment object per Github commit.

The last step is to create a rule (named `gitToSlackRule`) that connects the trigger with our `gittoSlack2` action. You can create it with the following commands:

`wsk rule create gitToSlackRule myGitTrigger gitToSlack2`

`wsk rule enable gitToSlackRule`

The second command is to activate the created rule.

* Now, to test your action, open the repository you created at the beginning on Github and edit the README.MD file. Enter your commit message and push your change to master. You could also clone the repository to your local space and push it back to Github after changing it. Both actions will trigger the `push` event, fire the trigger and execute the `gitToSlack2`action.

## 7. Optional: Include the Watson translation API

Thanks to the simplicity of Whisk actions, we can easily extend the functionality of our application to incorporate translation by an IBM Bluemix Watson service. The following code translates the commit message into Spanish.

```
function main(params){
  var commits = params.commits;

  var doTranslate = function(i, commit, next) {
    var translateParams = {
      payload: commit.message,
      translateFrom: "en",
      translateTo: "fr",
      username: "USERNAME",
      password: "PASSWORD",
    };
    whisk.invoke({
      name: '/whisk.system/watson/translate',
      parameters: translateParams,
      blocking: true,
      next: function(error, activation) {
        if (next) next(i, activation.result);
      }
    });
  };

  var slackPost = {
    username: "Octocat",
    text: params.sender.login + " pushed " + commits.length +" commits to " + params.repository.full_name,
    icon_emoji: ":github_icon:"
  };

  slackPost.attachments = [];

  var completed = 0;
  for(var i = 0; i < commits.length; i++){

    doTranslate(i, commits[i], function(i,translatedCommitMessage) {
      slackPost.attachments.push(
       {
         "color": "#36a64f",
         "author_name": commits[i].author.username,
         "title": commits[i].id,
         "title_link": commits[i].url,
         "text": commits[i].timestamp,
         "thumb_url": params.sender.avatar_url,
         "fields": [
           {
             "title": "English",
             "value": commits[i].message,
             "short": true
           },
           {
             "title": "French",
             "value": translatedCommitMessage.payload,
             "short": true
           }
         ]
       });

       if (++completed == commits.length) {
         whisk.invoke({
           name: "mySlack/post",
           parameters: slackPost,
           blocking: true,
           next: function(error, activation) {
               whisk.done(undefined, error);
           }
         });
       }
    });
  }

  return whisk.async();
}
```

Just update the existing `gitToSlack2` action using the command:

`wsk action update gitToSlack2 /path/to/gitToSlack2.js`

After that, change something in your repository and push the changes to Github. As you will see in your Slack channel, the message contains the Spanish translation of the commit message now. Congratulations!
