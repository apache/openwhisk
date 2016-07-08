
# Creating and invoking OpenWhisk actions


Actions are stateless code snippets that run on the OpenWhisk platform. An action can be a JavaScript function, a Swift function, or a custom executable program packaged in a Docker container. For example, an action can be used to detect the faces in an image, aggregate a set of API calls, or post a Tweet.

Actions can be explicitly invoked, or run in response to an event. In either case, a run of an action results in an activation record that is identified by a unique activation ID. The input to an action and the result of an action are a dictionary of key-value pairs, where the key is a string and the value a valid JSON value.

Actions can be composed of calls to other actions or a defined sequence of actions.

## Creating and invoking JavaScript actions

The following sections guide you through working with actions in JavaScript. Beginning with the creation and invocation of a simple action, you will move on to adding parameters to an action and invoking that action with parameters, setting default parameters and invoking them, creating asynchronous actions, and finally working with action sequences.


### Creating and invoking a simple JavaScript action

Review the following steps and examples to create your first JavaScript action.

1. Create a JavaScript file with the following content. For this example, the file name is 'hello.js'.
  
  ```
  function main() {
      return {payload: 'Hello world'};
  }
  ```

  The JavaScript file might contain additional functions. However, by convention, a function called `main` must exist to provide the entry point for the action.

2. Create an action from the following JavaScript function. For this example, the action is called 'hello'.

  ```
  $ wsk action create hello hello.js
  ```
  ```
  ok: created action hello
  ```

3. List the actions you have created:
  
  ```
  $ wsk action list
  ```
  ```
  actions
  hello       private
  ```

  You can see the `hello` action you just created.

4. After you create your action, you can run it in the cloud in OpenWhisk with the 'invoke' command. You can invoke actions with a *blocking* invocation or a *non-blocking* invocation by specifying a flag in the command. A blocking invocation waits until the action runs to completion and returns a result. This example uses the blocking parameter, `--blocking`:

  ```
  $ wsk action invoke --blocking hello
  ```
  ```
  ok: invoked hello with id 44794bd6aab74415b4e42a308d880e5b
  {
      "result": {
          "payload": "Hello world"
      },
      "status": "success",
      "success": true
  }
  ```

  The command outputs two important pieces of information:
  * The activation ID (`44794bd6aab74415b4e42a308d880e5b`)
  * The invocation result

  The result in this case is the string `Hello world` returned by the JavaScript function. The activation ID can be used to retrieve the logs or result of the invocation at a future time.  

5. If you don't need the action result right away, you can omit the `--blocking` flag to make a non-blocking invocation. You can get the result later by using the activation ID. See the following example:

  ```
  $ wsk action invoke hello
  ```
  ```
  ok: invoked hello with id 6bf1f670ee614a7eb5af3c9fde813043
  ```

  ```
  $ wsk activation result 6bf1f670ee614a7eb5af3c9fde813043
  ```
  ```
  {
      "payload": "Hello world"
  }
  ```

6. If you forget to record the activation ID, you can get a list of activations ordered from the most recent to the oldest. Run the following command to get a list of your activations:

  ```
  $ wsk activation list
  ```
  ```
  activations
  44794bd6aab74415b4e42a308d880e5b         hello
  6bf1f670ee614a7eb5af3c9fde813043         hello
  ```

### Passing parameters to an action

Parameters can be passed to the action when it is invoked.

1. Use parameters in the action. For example, update the 'hello.js' file with the following content:
  
  ```
  function main(params) {
      return {payload:  'Hello, ' + params.name + ' from ' + params.place};
  }
  ```

  The input parameters are passed as a JSON object parameter to the `main` function. Notice how the `name` and `place` parameters are retrieved from the `params` object in this example.

2. Update the `hello` action and invoke the action, while passing it `name` and `place` parameter values. See the following example:
  
  ```
  $ wsk action update hello hello.js
  ```
  ```
  $ wsk action invoke --blocking --result hello --param name 'Bernie' --param place 'Vermont'
  ```
  ```
  {
      "payload": "Hello, Bernie from Vermont"
  }
  ```

  Notice the use of the `--param` option to specify a parameter name and value, and the `--result` option to display only the invocation result.

### Setting default parameters

Actions can be invoked with multiple named parameters. Recall that the `hello` action from the previous example expects two parameters: the *name* of a person, and the *place* where they're from.

Rather than pass all the parameters to an action every time, you can bind certain parameters. The following example binds the *place* parameter so that the action defaults to the place "Vermont":
 
1. Update the action using the `--param` option to bind parameter values.

  ```
  $ wsk action update hello --param place 'Vermont'
  ```

2. Invoke the action, only passing the `name` parameter this time.

  ```
  $ wsk action invoke --blocking --result hello --param name 'Bernie'
  ```
  ```
  {
      "payload": "Hello, Bernie from Vermont"
  }
  ```

  Note that you did not need to specify the place parameter when invoking the action. Bound parameters can still be overwritten by specifying the parameter value at invocation time.

3. Invoke the action, passing both `name` and `place` values. The latter overwrites the value bound to the action.

  ```
  $ wsk action invoke --blocking --result hello --param name 'Bernie' --param place 'Washington, DC'
  ```
  ```
  {  
      "payload": "Hello, Bernie from Washington, DC"
  }
  ```

### Creating asynchronous actions

JavaScript functions that continue execution in a callback function might need to return the activation result after the `main` function has returned. You can accomplish this using the `whisk.async()` and `whisk.done()` functions in your action.

1. Save the following content in a file called `asyncAction.js`.

  ```
  function main() {
      setTimeout(function() {
          return whisk.done({done: true});
      }, 20000);
      return whisk.async();
  }
  ```

  Notice that the `main` function returns immediately, and the `whisk.async()` return value indicates that this activation should continue running.

  The `setTimeout()` JavaScript function in this case waits for twenty seconds before calling the callback function, where the call to `whisk.done()` indicates that the activation is complete.

2. Run the following commands to create the action and invoke it:

  ```
  $ wsk action create asyncAction asyncAction.js
  ```
  ```
  $ wsk action invoke --blocking --result asyncAction
  ```
  ```
  {
      "done": true
  }
  ```

  Notice that you performed a blocking invocation of an asynchronous action.

3. Fetch the activation log to see how long the activation took to complete:

  ```
  $ wsk activation list --limit 1 asyncAction
  ```
  ```
  activations
  b066ca51e68c4d3382df2d8033265db0             asyncAction
  ```


  ```
  $ wsk activation get b066ca51e68c4d3382df2d8033265db0
  ```
 ```
  {
      "start": 1455881628103,
      "end":   1455881648126,
      ...
  }
  ```

  Comparing the `start` and `end` timestamps in the activation record, you can see that this activation took slightly over twenty seconds to complete.


### Using actions to call an external API

The examples so far have been self-contained JavaScript functions. You can also create an action that calls an external API.

This example invokes a Yahoo Weather service to get the current conditions at a specific location. 

1. Save the following content in a file called `weather.js`.
  ```
    var request = require('request');
    
    function main(params) {
        var location = params.location || 'Vermont';
        var url = 'https://query.yahooapis.com/v1/public/yql?q=select item.condition from weather.forecast where woeid in (select woeid from geo.places(1) where text="' + location + '")&format=json';
    
        request.get(url, function(error, response, body) {
            var condition = JSON.parse(body).query.results.channel.item.condition;
            var text = condition.text;
            var temperature = condition.temp;
            var output = 'It is ' + temperature + ' degrees in ' + location + ' and ' + text;
            whisk.done({msg: output});
        });
    
        return whisk.async();
    }
  ```

  Note that the action in the example uses the JavaScript `request` library to make an HTTP request to the Yahoo Weather API, and extracts fields from the JSON result. The [References](./reference.md#runtime-environment) detail the Node.js packages that you can use in your actions.
  
  This example also shows the need for asynchronous actions. The action returns `whisk.async()` to indicate that the result of this action is not available yet when the function returns. Instead, the result is available in the `request` callback after the HTTP call completes, and is passed as an argument to the `whisk.done()` function.

2. Run the following commands to create the action and invoke it:
  ```
  $ wsk action create weather weather.js
  ```
  ```
  $ wsk action invoke --blocking --result weather --param location 'Brooklyn, NY'
  ```
  ```
  {
      "msg": "It is 28 degrees in Brooklyn, NY and Cloudy"
  }
  ```

### Creating action sequences

You can create an action that chains together a sequence of actions.

Several utility actions are provided in a package called `/whisk.system/util` that you can use to create your first sequence. You can learn more about packages in the [Packages](./packages.md) section.

1. Display the actions in the `/whisk.system/util` package.
  
  ```
  $ wsk package get --summary /whisk.system/util
  ```
  ```
  package /whisk.system/util
   action /whisk.system/util/cat: Concatenate array of strings
   action /whisk.system/util/head: Filter first K array elements and discard rest
   action /whisk.system/util/date: Get current date and time
   action /whisk.system/util/sort: Sort array
   action /whisk.system/util/split: Splits a string into an array of strings
  ```

  You will be using the `split` and `sort` actions in this example.

2. Create an action sequence so that the result of one action is passed as an argument to the next action.
  
  ```
  $ wsk action create myAction --sequence /whisk.system/util/split,/whisk.system/util/sort
  ```

  This action sequence converts some lines of text to an array, and sorts the lines.

3. Before you invoke the action sequence, create a text file called 'haiku.txt' with a few lines of text:

  ```
  Over-ripe sushi,
  The Master
  Is full of regret.
  ```

4. Invoke the action:
  
  ```
  $ wsk action invoke --blocking --result myAction --param payload "$(cat haiku.txt)"
  ```
  ```
  {
      "length": 3,
      "lines": [
          "Is full of regret.",
          "Over-ripe sushi,",
          "The Master"
      ]
  }
  ```

  In the result, you see that the lines are sorted.

**Note**: For more information on invoking action sequences with multiple named parameters, see [Setting default parameters](./actions.md#setting-default-parameters)


## Creating Python actions

The process of creating Python actions is similar to that of JavaScript actions. The following sections guide you through creating and invoking a single Python action, and adding parameters to that action.

### Creating and invoking an action

An action is simply a top-level Python function, which means it is necessary to have a method named `main`. For example, create a file called
`hello.py` with the following content:

```
    def main(dict):
        name = dict.get("name", "stranger")
        greeting = "Hello " + name + "!"
        print(greeting)
        return {"greeting": greeting}
```

Python actions always consume a dictionary and produce a dictionary.

You can create an OpenWhisk action called `helloPython` from this function as
follows:

```
$ wsk action create helloPython hello.py
```

When using the command line and a `.py` source file, you do not need to
specify that you are creating a Python action (as opposed to a JavaScript action);
the tool determines that from the file extension.



## Creating Swift actions

The process of creating Swift actions is similar to that of JavaScript actions. The following sections guide you through creating and invoking a single swift action, and adding parameters to that action.

You can also use the online [Swift Sandbox](https://swiftlang.ng.bluemix.net) to test your Swift code without having to install Xcode on your machine.

### Creating and invoking an action

An action is simply a top-level Swift function. For example, create a file called
`hello.swift` with the following content:

```
  func main(args: [String:Any]) -> [String:Any] {
      if let name = args["name"] as? String {
          return [ "greeting" : "Hello \(name)!" ]
      } else {
          return [ "greeting" : "Hello stranger!" ]
      }
  }
```

Swift actions always consume a dictionary and produce a dictionary.

You can create a OpenWhisk action called `helloSwift` from this function as
follows:

```
$ wsk action create helloSwift hello.swift
```

When using the command line and a `.swift` source file, you do not need to
specify that you are creating a Swift action (as opposed to a JavaScript action);
the tool determines that from the file extension.

Action invocation is the same for Swift actions as it is for JavaScript actions:

```
$ wsk action invoke --blocking --result helloSwift --param name World
```

```
  {
      "greeting": "Hello World!"
  }
```

**Attention:** Swift actions run in a Linux environment. Swift on Linux is still in
development, and OpenWhisk usually uses the latest available release, which is not necessarily stable. In addition, the version of Swift that is used with OpenWhisk might be inconsistent with versions of Swift from stable releases of XCode on MacOS.

## Creating Java actions

The process of creating Java actions is similar to that of JavaScript and Swift actions. The following sections guide you through creating and invoking a single Java action, and adding parameters to that action.

In order to compile, test and archive Java files, you must have a [JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) installed locally.

### Creating and invoking an action

A Java action is a Java program with a method called `main` that has the exact signature below:
```
public static com.google.gson.JsonObject main(com.google.gson.JsonObject);
```

For example, create a Java file called `Hello.java` with the following content:

```
import com.google.gson.JsonObject;

public class Hello {

    public static JsonObject main(JsonObject args) {

        String name = "stranger";
        if (args.has("name"))
            name = args.getAsJsonPrimitive("name").getAsString();

        JsonObject response = new JsonObject();
        response.addProperty("greeting", "Hello " + name + "!");
        return response;

    }
}
```

Then compile `Hello.java` into a jar file `hello.jar` as follows:
```
$ javac Hello.java
$ jar cvf hello.jar Hello.class

```
Note that [google-gson](https://github.com/google/gson) must exist in your Java CLASSPATH when compiling the Java file.

You can create a OpenWhisk action called `helloJava` from this jar file as
follows:

```
$ wsk action create helloJava hello.jar
```

When using the command line and a `.jar` source file, you do not need to
specify that you are creating a Java action;
the tool determines that from the file extension.

Action invocation is the same for Java actions as it is for Swift and JavaScript actions:

```
$ wsk action invoke --blocking --result helloJava --param name World
```

```
  {
      "greeting": "Hello World!"
  }
```

Note that if the jar file has more than one class with a main method matching required signature, the CLI tool will use the first one reported by `jar -tf`.

## Creating Docker actions

With OpenWhisk Docker actions, you can write your actions in any language.

Your code is compiled into an executable binary and embedded into a Docker image. The binary program interacts with the system by taking input from `stdin` and replying through `stdout`.

As a prerequisite, you must have a Docker Hub account.  To set up a free Docker ID and account, go to [Docker Hub](https://hub.docker.com).

For the instructions that follow, assume that the user ID is "janesmith" and the password is "janes_password".  Assuming that the CLI has already been set up, three steps are required to set up a custom binary for use by OpenWhisk.  After that, the uploaded Docker image can be used as an action.

1. Download the Docker skeleton. You can download it by using the CLI as follows:

  ```
  $ wsk sdk install docker
  ```
  ```
  The Docker skeleton is now installed at the current directory.
  ```

  ```
  $ ls dockerSkeleton/
  ```
  ```
  Dockerfile      README.md       buildAndPush.sh client          server
  ```

  The skeleton is a Docker container template where you can inject your code in the form of custom binaries.

2. Set up your custom binary in the blackbox skeleton. The skeleton already includes a C program that you can use.

  ```
  $ cat ./dockerSkeleton/client/example.c
  ```
  ```
  #include <stdio.h>
  
  int main(int argc, char *argv[]) {
      printf("{ \"msg\": \"Hello from arbitrary C program!\", \"args\": %s, \"argc\": %d }",
             (argc == 1) ? "undefined" : argv[1]);
  }
  ```

  You can modify this file as needed.

3. Build the Docker image and upload it using a supplied script. You must first run `docker login` to authenticate, and then run the script with a chosen image name.

  ```
  $ docker login -u janesmith -p janes_password
  ```
  ```
  $ cd dockerSkeleton
  ```
  ```
  $ ./buildAndPush.sh janesmith/blackboxdemo
  ```

  Note that part of the example.c file is compiled as part of the Docker image build process, so you do not need C compiled on your machine.

4. To create an action from a Docker image rather than a supplied JavaScript file, add `--docker` and replace the JavaScript file name with the Docker image name.

  ```
  $ wsk action create --docker example janesmith/blackboxdemo
  ```
  ```
  $ wsk action invoke --blocking --result example --param payload Rey
  ```
  ```
  {
      "args": {
          "payload": "Rey"
      },
      "msg": "Hello from arbitrary C program!"
  }
  ```

5. To update a Docker action, run `buildAndPush.sh` to refresh the image on Docker Hub, then you have to run `wsk action update` to make the system to fetch the new image. New invocations will start using the new image and not a warm image with the old code.

  ```
  $ ./buildAndPush.sh janesmith/blackboxdemo
  ```
  ```
  $ wsk action update --docker example janesmith/blackboxdemo
  ```

You can find more information about creating Docker actions in the [References](./reference.md#docker-actions) section.


## Watching action output

OpenWhisk actions might be invoked by other users, in response to various events, or as part of an action sequence. In such cases it can be useful to monitor the invocations.

You can use the OpenWhisk CLI to watch the output of actions as they are invoked.

1. Issue the following command from a shell:
  ```
  $ wsk activation poll
  ```

  This command starts a polling loop that continuously checks for logs from activations.

2. Switch to another window and invoke an action:

  ```
  $ wsk action invoke /whisk.system/samples/helloWorld --param payload Bob
  ```
  ```
  ok: invoked /whisk.system/samples/helloWorld with id 7331f9b9e2044d85afd219b12c0f1491
  ```

3. Observe the activation log in the polling window:

  ```
  Activation: helloWorld (7331f9b9e2044d85afd219b12c0f1491)
    2016-02-11T16:46:56.842065025Z stdout: hello bob!
  ```

  Similarly, whenever you run the poll utility, you see in real time the logs for any actions running on your behalf in OpenWhisk.

## Deleting actions

You can clean up by deleting actions that you do not want to use.

1. Run the following command to delete an action:
  ```
  $ wsk action delete hello
  ```
  ```
  ok: deleted hello
  ```

2. Verify that the action no longer appears in the list of actions.
  ```
  $ wsk action list
  ```
  ```
  actions
  ```
