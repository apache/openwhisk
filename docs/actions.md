
# Creating and invoking OpenWhisk actions


Actions are stateless code snippets that run on the OpenWhisk platform. An action can be written as a JavaScript, Swift, or Python function, a Java method, or a custom executable program packaged in a Docker container. For example, an action can be used to detect the faces in an image, respond to a database change, aggregate a set of API calls, or post a Tweet.

Actions can be explicitly invoked, or run in response to an event. In either case, each run of an action results in an activation record that is identified by a unique activation ID. The input to an action and the result of an action are a dictionary of key-value pairs, where the key is a string and the value a valid JSON value. Actions can also be composed of calls to other actions or a defined sequence of actions.

Learn how to create, invoke, and debug actions in your preferred development environment:

* [JavaScript](#creating-and-invoking-javascript-actions)
* [Swift](#creating-swift-actions)
* [Python](#creating-python-actions)
* [Java](#creating-java-actions)
* [PHP](#creating-php-actions)
* [Docker](#creating-docker-actions)

In addition, learn about:

* [Watching action output](#watching-action-output)
* [Listing actions](#listing-actions)
* [Deleting actions](#deleting-actions)
* [Accessing action metadata within the action body](#accessing-action-metadata-within-the-action-body)


## Creating and invoking JavaScript actions

The following sections guide you through working with actions in JavaScript. You begin with the creation and invocation of a simple action. Then, you move on to adding parameters to an action and invoking that action with parameters. Next is setting default parameters and invoking them. Then, you create asynchronous actions and, finally, work with action sequences.


### Creating and invoking a simple JavaScript action

Review the following steps and examples to create your first JavaScript action.

1. Create a JavaScript file with the following content. For this example, the file name is 'hello.js'.

  ```javascript
  function main() {
      return {payload: 'Hello world'};
  }
  ```

  The JavaScript file might contain additional functions. However, by convention, a function called `main` must exist to provide the entry point for the action.

2. Create an action from the following JavaScript function. For this example, the action is called 'hello'.

  ```
  wsk action create hello hello.js
  ```
  ```
  ok: created action hello
  ```

3. List the actions that you have created:

  ```
  wsk action list
  ```
  ```
  actions
  hello       private
  ```

  You can see the `hello` action you just created.

4. After you create your action, you can run it in the cloud in OpenWhisk with the 'invoke' command. You can invoke actions with a *blocking* invocation (i.e., request/response style) or a *non-blocking* invocation by specifying a flag in the command. A blocking invocation request will _wait_ for the activation result to be available. The wait period is the lesser of 60 seconds or the action's configured [time limit](./reference.md#per-action-timeout-ms-default-60s). The result of the activation is returned if it is available within the wait period. Otherwise, the activation continues processing in the system and an activation ID is returned so that one may check for the result later, as with non-blocking requests (see [here](#watching-action-output) for tips on monitoring activations).

  This example uses the blocking parameter, `--blocking`:

  ```
  wsk action invoke --blocking hello
  ```
  ```
  ok: invoked hello with id 44794bd6aab74415b4e42a308d880e5b
  ```
  ```json
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
  * The invocation result if it is available within the expected wait period

  The result in this case is the string `Hello world` returned by the JavaScript function. The activation ID can be used to retrieve the logs or result of the invocation at a future time.  

5. If you don't need the action result right away, you can omit the `--blocking` flag to make a non-blocking invocation. You can get the result later by using the activation ID. See the following example:

  ```
  wsk action invoke hello
  ```
  ```
  ok: invoked hello with id 6bf1f670ee614a7eb5af3c9fde813043
  ```

  ```
  wsk activation result 6bf1f670ee614a7eb5af3c9fde813043
  ```
  ```json
  {
      "payload": "Hello world"
  }
  ```

6. To access the most recent activation record, activation results or activation logs, use the `--last` or `-l` flag. Run the following command to get your last activation result.

  ```
  wsk activation result --last
  ```
  ```json
  {
      "payload": "Hello world"
  }
  ```

  Note that you should not use an activation ID with the flag `--last`.

7. If you forget to record the activation ID, you can get a list of activations ordered from the most recent to the oldest. Run the following command to get a list of your activations:

  ```
  wsk activation list
  ```
  ```
  activations
  44794bd6aab74415b4e42a308d880e5b         hello
  6bf1f670ee614a7eb5af3c9fde813043         hello
  ```

### Passing parameters to an action

Parameters can be passed to the action when it is invoked.

1. Use parameters in the action. For example, update the 'hello.js' file with the following content:

  ```javascript
  function main(params) {
      return {payload:  'Hello, ' + params.name + ' from ' + params.place};
  }
  ```

  The input parameters are passed as a JSON object parameter to the `main` function. Notice how the `name` and `place` parameters are retrieved from the `params` object in this example.

2. Update the `hello` action and invoke the action, while passing it `name` and `place` parameter values. See the following example:

  ```
  wsk action update hello hello.js
  ```

3.  Parameters can be provided explicitly on the command-line, or by supplying a file containing the desired parameters

  To pass parameters directly through the command-line, supply a key/value pair to the `--param` flag:
  ```
  wsk action invoke --result hello --param name Bernie --param place Vermont
  ```

  In order to use a file containing parameter content, create a file containing the parameters in JSON format. The
  filename must then be passed to the `param-file` flag:

  Example parameter file called parameters.json:
  ```json
  {
      "name": "Bernie",
      "place": "Vermont"
  }
  ```

  ```
  wsk action invoke --result hello --param-file parameters.json
  ```

  ```json
  {
      "payload": "Hello, Bernie from Vermont"
  }
  ```

  Notice the use of the `--result` option: it implies a blocking invocation where the CLI waits for the activation to complete and then
  displays only the result. For convenience, this option may be used without `--blocking` which is automatically inferred.

  Additionally, if parameter values specified on the command-line are valid JSON, then they will be parsed and sent to your action as a structured object. For example, if we update our hello action to:

  ```javascript
  function main(params) {
      return {payload:  'Hello, ' + params.person.name + ' from ' + params.person.place};
  }
  ```

  Now the action expects a single `person` parameter to have fields `name` and `place`. If we invoke the action with a single `person` parameter that is valid JSON:

  ```
  wsk action invoke --result hello -p person '{"name": "Bernie", "place": "Vermont"}'
  ```

  The result is the same because the CLI automatically parses the `person` parameter value into the structured object that the action now expects:
  ```json
  {
      "payload": "Hello, Bernie from Vermont"
  }
  ```

### Setting default parameters

Actions can be invoked with multiple named parameters. Recall that the `hello` action from the previous example expects two parameters: the *name* of a person, and the *place* where they're from.

Rather than pass all the parameters to an action every time, you can bind certain parameters. The following example binds the *place* parameter so that the action defaults to the place "Vermont":

1. Update the action by using the `--param` option to bind parameter values, or by passing a file that contains the parameters to `--param-file`

  To specify default parameters explicitly on the command-line, provide a key/value pair to the `param` flag:

  ```
  wsk action update hello --param place Vermont
  ```

  Passing parameters from a file requires the creation of a file containing the desired content in JSON format.
  The filename must then be passed to the `-param-file` flag:

  Example parameter file called parameters.json:
  ```json
  {
      "place": "Vermont"
  }
  ```

  ```
  wsk action update hello --param-file parameters.json
  ```

2. Invoke the action, passing only the `name` parameter this time.

  ```
  wsk action invoke --result hello --param name Bernie
  ```
  ```json
  {
      "payload": "Hello, Bernie from Vermont"
  }
  ```

  Notice that you did not need to specify the place parameter when you invoked the action. Bound parameters can still be overwritten by specifying the parameter value at invocation time.

3. Invoke the action, passing both `name` and `place` values. The latter overwrites the value that is bound to the action.

  Using the `--param` flag:

  ```
  wsk action invoke --result hello --param name Bernie --param place "Washington, DC"
  ```

  Using the `--param-file` flag:

  File parameters.json:
  ```json
  {
    "name": "Bernie",
    "place": "Washington, DC"
  }
  ```

  ```
  wsk action invoke --result hello --param-file parameters.json
  ```

  ```json
  {  
      "payload": "Hello, Bernie from Washington, DC"
  }
  ```

### Getting an action URL

An action can be invoked through the REST interface via an HTTPS request. To get an action URL, execute the following command:

```
wsk action get actionName --url
```
```
ok: got action actionName
https://${APIHOST}/api/v1/namespaces/${NAMESPACE}/actions/actionName
```

**Note:** Authentication must be provided when invoking an action via an HTTPS request. For more information regarding
action invocations using the REST interface, see
[Using REST APIs with OpenWhisk](rest_api.md#actions).

### Saving action code

Code associated with an existing action is fetched and saved locally. Saving is performed on all actions except sequences and docker actions. When saving action code to a file, the code is saved in the current working directory, and the saved file path is displayed.

1. Save action code to a filename that corresponds with an existing action name. A file extension that corresponds to the action kind is  used, or an extension of `.zip` will be used for action code that is a zip file.
  ```
  wsk action get actionName --save
  ```
  ```
  ok: saved action code to /absolutePath/currentDirectory/actionName.js
  ```

2. Instead of allowing the CLI to determine the filename and extension  of the saved code, a custom filename and extension can be provided by using the `--save-as` flag.
  ```
  wsk action get actionName --save-as codeFile.js
  ```
  ```
  ok: saved action code to /absolutePath/currentDirectory/codeFile.js
  ```

### Creating asynchronous actions

JavaScript functions that run asynchronously may need to return the activation result after the `main` function has returned. You can accomplish this by returning a Promise in your action.

1. Save the following content in a file called `asyncAction.js`.

  ```javascript
  function main(args) {
       return new Promise(function(resolve, reject) {
         setTimeout(function() {
           resolve({ done: true });
         }, 2000);
      })
   }
  ```

  Notice that the `main` function returns a Promise, which indicates that the activation hasn't completed yet, but is expected to in the future.

  The `setTimeout()` JavaScript function in this case waits for two seconds before calling the callback function.  This represents the asynchronous code and goes inside the Promise's callback function.

  The Promise's callback takes two arguments, resolve and reject, which are both functions.  The call to `resolve()` fulfills the Promise and indicates that the activation has completed normally.

  A call to `reject()` can be used to reject the Promise and signal that the activation has completed abnormally.

2. Run the following commands to create the action and invoke it:

  ```
  wsk action create asyncAction asyncAction.js
  ```
  ```
  wsk action invoke --result asyncAction
  ```
  ```json
  {
      "done": true
  }
  ```

  Notice that you performed a blocking invocation of an asynchronous action.

3. Fetch the activation log to see how long the activation took to complete:

  ```
  wsk activation list --limit 1 asyncAction
  ```
  ```
  activations
  b066ca51e68c4d3382df2d8033265db0             asyncAction
  ```


  ```
  wsk activation get b066ca51e68c4d3382df2d8033265db0
  ```
 ```json
  {
      "start": 1455881628103,
      "end":   1455881648126,
      ...
  }
  ```

  Comparing the `start` and `end` time stamps in the activation record, you can see that this activation took slightly over two seconds to complete.

### Using actions to call an external API

The examples so far have been self-contained JavaScript functions. You can also create an action that calls an external API.

This example invokes a Yahoo Weather service to get the current conditions at a specific location.

1. Save the following content in a file called `weather.js`.

  ```javascript
  var request = require('request');

  function main(params) {
      var location = params.location || 'Vermont';
      var url = 'https://query.yahooapis.com/v1/public/yql?q=select item.condition from weather.forecast where woeid in (select woeid from geo.places(1) where text="' + location + '")&format=json';

      return new Promise(function(resolve, reject) {
          request.get(url, function(error, response, body) {
              if (error) {
                  reject(error);
              }
              else {
                  var condition = JSON.parse(body).query.results.channel.item.condition;
                  var text = condition.text;
                  var temperature = condition.temp;
                  var output = 'It is ' + temperature + ' degrees in ' + location + ' and ' + text;
                  resolve({msg: output});
              }
          });
      });
  }
  ```

  Note that the action in the example uses the JavaScript `request` library to make an HTTP request to the Yahoo Weather API, and extracts fields from the JSON result. The [References](./reference.md#javascript-runtime-environments) detail the Node.js packages that you can use in your actions.

  This example also shows the need for asynchronous actions. The action returns a Promise to indicate that the result of this action is not available yet when the function returns. Instead, the result is available in the `request` callback after the HTTP call completes, and is passed as an argument to the `resolve()` function.

2. Run the following commands to create the action and invoke it:

  ```
  wsk action create weather weather.js
  ```
  ```
  wsk action invoke --result weather --param location "Brooklyn, NY"
  ```
  ```json
  {
      "msg": "It is 28 degrees in Brooklyn, NY and Cloudy"
  }
  ```

### Packaging an action as a Node.js module

As an alternative to writing all your action code in a single JavaScript source file, you can write an action as a `npm` package. Consider as an example a directory with the following files:

First, `package.json`:

```json
{
  "name": "my-action",
  "main": "index.js",
  "dependencies" : {
    "left-pad" : "1.1.3"
  }
}
```

Then, `index.js`:

```javascript
function myAction(args) {
    const leftPad = require("left-pad")
    const lines = args.lines || [];
    return { padded: lines.map(l => leftPad(l, 30, ".")) }
}

exports.main = myAction;
```

Note that the action is exposed through `exports.main`; the action handler itself can have any name, as long as it conforms to the usual signature of accepting an object and returning an object (or a `Promise` of an object). Per Node.js convention, you must either name this file `index.js` or specify the file name you prefer as the `main` property in package.json.

To create an OpenWhisk action from this package:

1. Install first all dependencies locally

  ```
  $ npm install
  ```

2. Create a `.zip` archive containing all files (including all dependencies):

  ```
  $ zip -r action.zip *
  ```

  > Please note: Using the Windows Explorer action for creating the zip file will result in an incorrect structure. OpenWhisk zip actions must have `package.json` at the root of the zip, while Windows Explorer will put it inside a nested folder. The safest option is to use the command line `zip` command as shown above.

3. Create the action:

  ```
  wsk action create packageAction --kind nodejs:6 action.zip
  ```

  Note that when creating an action from a `.zip` archive using the CLI tool, you must explicitly provide a value for the `--kind` flag.

4. You can invoke the action like any other:

  ```
  wsk action invoke --result packageAction --param lines "[\"and now\", \"for something completely\", \"different\" ]"
  ```
  ```json
  {
      "padded": [
          ".......................and now",
          "......for something completely",
          ".....................different"
      ]
  }
  ```

Finally, note that while most `npm` packages install JavaScript sources on `npm install`, some also install and compile binary artifacts. The archive file upload currently does not support binary dependencies but rather only JavaScript dependencies. Action invocations may fail if the archive includes binary dependencies.

## Creating action sequences

You can create an action that chains together a sequence of actions.

Several utility actions are provided in a package called `/whisk.system/utils` that you can use to create your first sequence. You can learn more about packages in the [Packages](./packages.md) section.

1. Display the actions in the `/whisk.system/utils` package.

  ```
  wsk package get --summary /whisk.system/utils
  ```
  ```
  package /whisk.system/utils: Building blocks that format and assemble data
   (parameters: none defined)
     action /whisk.system/utils/namespace: Returns namespace for the authorization key used to invoke this action
       (parameters: none defined)
     action /whisk.system/utils/date: Current date and time
       (parameters: none defined)
     action /whisk.system/utils/sort: Sorts an array
       (parameters: lines)
     action /whisk.system/utils/split: Split a string into an array
       (parameters: payload, separator)
     action /whisk.system/utils/hosturl: Returns the URL to activation an action or trigger
       (parameters: ext, path, trigger, web)
     ...
  ```

  You will be using the `split` and `sort` actions in this example.

2. Create an action sequence so that the result of one action is passed as an argument to the next action.

  ```
  wsk action create sequenceAction --sequence /whisk.system/utils/split,/whisk.system/utils/sort
  ```

  This action sequence converts some lines of text to an array, and sorts the lines.

3. Invoke the action:

  ```
  wsk action invoke --result sequenceAction --param payload "Over-ripe sushi,\nThe Master\nIs full of regret."
  ```
  ```json
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

**Note**: Parameters passed between actions in the sequence are explicit, except for default parameters.
Therefore parameters that are passed to the action sequence are only available to the first action in the sequence.
The result of the first action in the sequence becomes the input JSON object to the second action in the sequence (and so on).
This object does not include any of the parameters originally passed to the sequence unless the first action explicitly includes them in its result.
Input parameters to an action are merged with the action's default parameters, with the former taking precedence and overriding any matching default parameters.
For more information about invoking action sequences with multiple named parameters, see [Setting default parameters](./actions.md#setting-default-parameters).

## Creating Python actions

The process of creating Python actions is similar to that of JavaScript actions. The following sections guide you through creating and invoking a single Python action, and adding parameters to that action.

### Creating and invoking a Python action

An action is simply a top-level Python function. For example, create a file called `hello.py` with the following source code:

```python
def main(args):
    name = args.get("name", "stranger")
    greeting = "Hello " + name + "!"
    print(greeting)
    return {"greeting": greeting}
```

Python actions always consume a dictionary and produce a dictionary. The entry method for the action is `main` by default but may be specified explicitly when creating the action with the `wsk` CLI using `--main`, as with any other action type.

You can create an OpenWhisk action called `helloPython` from this function as follows:

```
wsk action create helloPython hello.py
```
The CLI automatically infers the type of the action from the source file extension. For `.py` source files, the action runs using a Python 2.7 runtime. You can also create an action that runs with Python 3.6 by explicitly specifying the parameter `--kind python:3`. See the Python [reference](./reference.md#python-actions) for more information about Python 2.7 vs. 3.6.

Action invocation is the same for Python actions as it is for JavaScript actions:

```
wsk action invoke --result helloPython --param name World
```

```json
  {
      "greeting": "Hello World!"
  }
```

### Packaging Python actions in zip files

You can package a Python action and dependent modules in a zip file.
The filename of the source file containing the entry point (e.g., `main`) must be `__main__.py`.
For example, to create an action with a helper module called `helper.py`, first create an archive containing your source files:

```bash
zip -r helloPython.zip __main__.py helper.py
```

and then create the action:

```bash
wsk action create helloPython --kind python:3 helloPython.zip
```

### Packaging Python actions with a virtual environment in zip files

Another way of packaging Python dependencies is using a virtual environment (`virtualenv`). This allows you to link additional packages
that may be installed via [`pip`](https://packaging.python.org/installing/) for example.
To ensure compatibility with the OpenWhisk container, package installations inside a virtualenv must be done in the target environment.
So the docker image `openwhisk/python2action` or `openwhisk/python3action` should be used to create a virtualenv directory for your action.

As with basic zip file support, the name of the source file containing the main entry point must be `__main__.py`. In addition, the virtualenv directory must be named `virtualenv`.
Below is an example scenario for installing dependencies, packaging them in a virtualenv, and creating a compatible OpenWhisk action.

1. Given a `requirements.txt` file that contains the `pip` modules and versions to install, run the following to install the dependencies and create a virtualenv using a compatible Docker image:
 ```bash
 docker run --rm -v "$PWD:/tmp" openwhisk/python3action sh \
   -c "cd tmp; virtualenv virtualenv; source virtualenv/bin/activate; pip install -r requirements.txt;"
 ```

2. Archive the virtualenv directory and any additional Python files:
 ```bash
 zip -r helloPython.zip virtualenv __main__.py
 ```

3. Create the action:
```bash
wsk action create helloPython --kind python:3 helloPython.zip
```

While the steps above are shown for Python 3.6, you can do the same for Python 2.7 as well.


## Creating PHP actions

The process of creating PHP actions is similar to that of JavaScript actions. The following sections guide you through creating and invoking a single PHP action, and adding parameters to that action.

### Creating and invoking a PHP action

An action is simply a top-level PHP function. For example, create a file called `hello.php` with the following source code:

```php
<?php
function main(array $args) : array
{
    $name = $args["name"] ?? "stranger";
    $greeting = "Hello $name!";
    echo $greeting;
    return ["greeting" => $greeting];
}
```

PHP actions always consume an associative array and return an associative array. The entry method for the action is `main` by default but may be specified explicitly when creating the action with the `wsk` CLI using `--main`, as with any other action type.

You can create an OpenWhisk action called `helloPHP` from this function as follows:

```
wsk action create helloPHP hello.php
```

The CLI automatically infers the type of the action from the source file extension. For `.php` source files, the action runs using a PHP 7.1 runtime. See the PHP [reference](./reference.md#php-actions) for more information.

Action invocation is the same for PHP actions as it is for JavaScript actions:

```
wsk action invoke --result helloPHP --param name World
```

```json
  {
      "greeting": "Hello World!"
  }
```

### Packaging PHP actions in zip files

You can package a PHP action along with other files and dependent packages in a zip file.
The filename of the source file containing the entry point (e.g., `main`) must be `index.php`.
For example, to create an action that includes a second file called `helper.php`, first create an archive containing your source files:

```bash
zip -r helloPHP.zip index.php helper.php
```

and then create the action:

```bash
wsk action create helloPHP --kind php:7.1 helloPHP.zip
```


## Creating Swift actions

The process of creating Swift actions is similar to that of JavaScript actions. The following sections guide you through creating and invoking a single swift action, and adding parameters to that action.

You can also use the online [Swift Sandbox](https://swiftlang.ng.bluemix.net) to test your Swift code without having to install Xcode on your machine.

### Creating and invoking an action

An action is simply a top-level Swift function. For example, create a file called
`hello.swift` with the following content:

```swift
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
wsk action create helloSwift hello.swift
```

The CLI automatically infers the type of the action from the source file extension. For `.swift` source files, the action runs using a Swift 3.1.1 runtime. You can also create an action that runs with Swift 3.0 by explicitly specifying the parameter `--kind swift:3`. See the Swift [reference](./reference.md#swift-actions) for more information about Swift 3.0 vs. 3.1.

**Note:** The actions you created using the kind `swift:3` will continue to work for a short period, however you should begin migrating your deployment scripts and recompiling your swift actions using the new kind `swift:3.1.1`. Support for Swift 3.0 is deprecated and will be removed soon.


Action invocation is the same for Swift actions as it is for JavaScript actions:

```
wsk action invoke --result helloSwift --param name World
```

```json
  {
      "greeting": "Hello World!"
  }
```

**Attention:** Swift actions run in a Linux environment. Swift on Linux is still in
development, and OpenWhisk usually uses the latest available release, which is not necessarily stable. In addition, the version of Swift that is used with OpenWhisk might be inconsistent with versions of Swift from stable releases of Xcode on MacOS.

### Packaging an action as a Swift executable

When you create an OpenWhisk Swift action with a Swift source file, it has to be compiled into a binary before the action is run. Once done, subsequent calls to the action are much faster until the container holding your action is purged. This delay is known as the cold-start delay.

To avoid the cold-start delay, you can compile your Swift file into a binary and then upload to OpenWhisk in a zip file. As you need the OpenWhisk scaffolding, the easiest way to create the binary is to build it within the same environment as it will be run in. These are the steps:

- Run an interactive Swift action container.
  ```
  docker run --rm -it -v "$(pwd):/owexec" openwhisk/action-swift-v3.1.1 bash
  ```
  This puts you in a bash shell within the Docker container.

- Copy the source code and prepare to build it.
  ```
  cp /owexec/hello.swift /swift3Action/spm-build/main.swift
  ```
  ```
  cat /swift3Action/epilogue.swift >> /swift3Action/spm-build/main.swift
  ```
  ```
  echo '_run_main(mainFunction:main)' >> /swift3Action/spm-build/main.swift
  ```
  Copy any additional source files to `/swift3Action/spm-build/`


- (Optional) Create the `Package.swift` file to add dependencies.
  ```swift
  import PackageDescription

  let package = Package(
    name: "Action",
        dependencies: [
            .Package(url: "https://github.com/apple/example-package-deckofplayingcards.git", majorVersion: 3),
            .Package(url: "https://github.com/IBM-Swift/CCurl.git", "0.2.3"),
            .Package(url: "https://github.com/IBM-Swift/Kitura-net.git", "1.7.10"),
            .Package(url: "https://github.com/IBM-Swift/SwiftyJSON.git", "15.0.1"),
            .Package(url: "https://github.com/watson-developer-cloud/swift-sdk.git", "0.16.0")
        ]
  )
  ```
  As you can see this example adds `swift-watson-sdk` and `example-package-deckofplayingcards` dependencies.
  Notice that `CCurl`, `Kitura-net` and `SwiftyJSON` are provided in the standard Swift action
and so you should include them in your own `Package.swift`.

- Copy Package.swift to spm-build directory
  ```
  cp /owexec/Package.swift /swift3Action/spm-build/Package.swift
  ```

- Change to the spm-build directory.
  ```
  cd /swift3Action/spm-build
  ```

- Compile your Swift Action.
  ```
  swift build -c release
  ```

- Create the zip archive.
  ```
  zip /owexec/hello.zip .build/release/Action
  ```

- Exit the Docker container.
  ```
  exit
  ```

  This has created hello.zip in the same directory as hello.swift.

- Upload it to OpenWhisk with the action name helloSwifty:
  ```
  wsk action update helloSwiftly hello.zip --kind swift:3.1.1
  ```

- To check how much faster it is, run
  ```
  wsk action invoke helloSwiftly --blocking
  ```


The time it took for the action to run is in the "duration" property and compare to the time it takes to run with a compilation step in the hello action.

## Creating Java actions

The process of creating Java actions is similar to that of JavaScript and Swift actions. The following sections guide you through creating and invoking a single Java action, and adding parameters to that action.

In order to compile, test and archive Java files, you must have a [JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) installed locally.

### Creating and invoking an action

A Java action is a Java program with a method called `main` that has the exact signature as follows:
```java
public static com.google.gson.JsonObject main(com.google.gson.JsonObject);
```

For example, create a Java file called `Hello.java` with the following content:

```java
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

Then, compile `Hello.java` into a JAR file `hello.jar` as follows:
```
javac Hello.java
```
```
jar cvf hello.jar Hello.class
```

**Note:** [google-gson](https://github.com/google/gson) must exist in your Java CLASSPATH when compiling the Java file.

You can create a OpenWhisk action called `helloJava` from this JAR file as
follows:

```
wsk action create helloJava hello.jar --main Hello
```

When you use the command line and a `.jar` source file, you do not need to
specify that you are creating a Java action;
the tool determines that from the file extension.

You need to specify the name of the main class using `--main`. An eligible main
class is one that implements a static `main` method as described above. If the
class is not in the default package, use the Java fully-qualified class name,
e.g., `--main com.example.MyMain`.

If needed you can also customize the method name of your Java action. This
can be done by specifying the Java fully-qualified method name of your action,
e.q., `--main com.example.MyMain#methodName`

Action invocation is the same for Java actions as it is for Swift and JavaScript actions:

```
wsk action invoke --result helloJava --param name World
```

```json
  {
      "greeting": "Hello World!"
  }
```

## Creating Docker actions

With OpenWhisk Docker actions, you can write your actions in any language.

Your code is compiled into an executable binary and embedded into a Docker image. The binary program interacts with the system by taking input from `stdin` and replying through `stdout`.

As a prerequisite, you must have a Docker Hub account.  To set up a free Docker ID and account, go to [Docker Hub](https://hub.docker.com).

For the instructions that follow, assume that the Docker user ID is `janesmith` and the password is `janes_password`.  Assuming that the CLI is already set up, three steps are required to set up a custom binary for use by OpenWhisk. After that, the uploaded Docker image can be used as an action.

1. Download the Docker skeleton. You can download it by using the CLI as follows:

  ```
  wsk sdk install docker
  ```
  ```
  The Docker skeleton is now installed at the current directory.
  ```

  ```
  $ ls dockerSkeleton/
  ```
  ```
  Dockerfile      README.md       buildAndPush.sh example.c
  ```

  The skeleton is a Docker container template where you can inject your code in the form of custom binaries.

2. Set up your custom binary in the blackbox skeleton. The skeleton already includes a C program that you can use.

  ```
  cat dockerSkeleton/example.c
  ```
  ```c
  #include <stdio.h>
  int main(int argc, char *argv[]) {
      printf("This is an example log message from an arbitrary C program!\n");
      printf("{ \"msg\": \"Hello from arbitrary C program!\", \"args\": %s }",
             (argc == 1) ? "undefined" : argv[1]);
  }
  ```

  You can modify this file as needed, or, add additional code and dependencies to the Docker image.
  In case of the latter, you may need to tweak the `Dockerfile` as necessary to build your executable.
  The binary must be located inside the container at `/action/exec`.

  The executable receives a single argument from the command line. It is a string serialization of the JSON
  object representing the arguments to the action. The program may log to `stdout` or `stderr`.
  By convention, the last line of output _must_ be a stringified JSON object which represents the result of the action.

3. Build the Docker image and upload it using a supplied script. You must first run `docker login` to authenticate, and then run the script with a chosen image name.

  ```
  docker login -u janesmith -p janes_password
  ```
  ```
  cd dockerSkeleton
  ```
  ```
  ./buildAndPush.sh janesmith/blackboxdemo
  ```

  Notice that part of the example.c file is compiled as part of the Docker image build process, so you do not need C compiled on your machine.
  In fact, unless you are compiling the binary on a compatible host machine, it may not run inside the container since formats will not match.

  Your Docker container may now be used as an OpenWhisk action.


  ```
  wsk action create example --docker janesmith/blackboxdemo
  ```

  Notice the use of `--docker` when creating an action. Currently all Docker images are assumed to be hosted on Docker Hub.
  The action may be invoked as any other OpenWhisk action.

  ```
  wsk action invoke --result example --param payload Rey
  ```
  ```json
  {
      "args": {
          "payload": "Rey"
      },
      "msg": "Hello from arbitrary C program!"
  }
  ```

  To update the Docker action, run buildAndPush.sh to upload the latest image to Docker Hub. This will allow the system to pull your new Docker image the next time it runs the code for your action.
  If there are no warm containers any new invocations will use the new Docker image.
  However, if there is a warm container using a previous version of your Docker image, any new invocations will continue to use that image unless you run `wsk action update`. This will indicate to the system that for new invocations it should execute a docker pull to get your new Docker image.

  ```
  ./buildAndPush.sh janesmith/blackboxdemo
  ```
  ```
  wsk action update example --docker janesmith/blackboxdemo
  ```

  You can find more information about creating Docker actions in the [References](./reference.md#docker-actions) section.

  *Note:* Previous version of the CLI supported `--docker` without a parameter and the image name was a positional argument.
  In order to allow Docker actions to accept initialization data via a (zip) file, similar to other actions kinds, we have
  normalized the user experience for Docker actions so that a positional argument if present must be a file (e.g., a zip file)
  instead. The image name must be specified following the `--docker` option. Furthermore, due to user feedback, we have added
  `--native` as shorthand for `--docker openwhisk/dockerskeleton` so that executables that run inside the standard Docker action
  SDK are more convenient to create and deploy.

  For example, the tutorial above created a binary executable inside the container locate at `/action/exec`. If you copy this file
  to your local file system and zip it into `exec.zip` then you can use the following commands to create a docker action which receives
  the executable as initialization data.

  ```bash
  wsk action create example exec.zip --native
  ```
  which is equivalent to the following command.
  ```bash
  wsk action create example exec.zip --docker openwhisk/dockerskeleton
  ```

## Watching action output

OpenWhisk actions might be invoked by other users, in response to various events, or as part of an action sequence. In such cases it can be useful to monitor the invocations.

You can use the OpenWhisk CLI to watch the output of actions as they are invoked.

1. Issue the following command from a shell:
  ```
  wsk activation poll
  ```

This command starts a polling loop that continuously checks for logs from activations.

2. Switch to another window and invoke an action:

  ```
  wsk action invoke /whisk.system/samples/helloWorld --param payload Bob
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


## Listing actions

You can list all the actions that you have created using `wsk action list`:

```
wsk action list
actions
/guest/packageB/A                  private nodejs:6
/guest/C                           private nodejs:6
/guest/A                           private nodejs:6
/guest/packageA/B                  private nodejs:6
/guest/packageA/A                  private nodejs:6
/guest/B                           private nodejs:6
```

Here, we see actions listed in order from most to least recently updated. For easier browsing, you can use the flag `--name-sort` or `-n` to sort the list alphabetically:

```
wsk action list --name-sort
actions
/guest/A                           private nodejs:6
/guest/B                           private nodejs:6
/guest/C                           private nodejs:6
/guest/packageA/A                  private nodejs:6
/guest/packageA/B                  private nodejs:6
/guest/packageB/A                  private nodejs:6
```

Notice that the list is now sorted alphabetically by namespace, then package name, and finally action name, with the default package (no specified package) listed at the top.

**Note**: The printed list is sorted alphabetically after it is received from the server. Other list flags such as `--limit` and `--skip` will be applied to the block of actions before they are received for sorting. To list actions in order by creation time, use the flag `--time`.

As you write more actions, this list gets longer and it can be helpful to group related actions into [packages](./packages.md). To filter your list of actions to just those within a specific package, you can use:

```
wsk action list [PACKAGE NAME]
```


## Deleting actions

You can clean up by deleting actions that you do not want to use.

1. Run the following command to delete an action:
  ```
  wsk action delete hello
  ```
  ```
  ok: deleted hello
  ```

2. Verify that the action no longer appears in the list of actions.
  ```
  wsk action list
  ```
  ```
  actions
  ```

## Accessing action metadata within the action body

The action environment contains several properties that are specific to the running action.
These allow the action to programmatically work with OpenWhisk assets via the REST API,
or set an internal alarm when the action is about to use up its allotted time budget.
The properties are accessible via the system environment for all supported runtimes:
Node.js, Python, Swift, Java and Docker actions when using the OpenWhisk Docker skeleton.

* `__OW_API_HOST` the API host for the OpenWhisk deployment running this action
* `__OW_API_KEY` the API key for the subject invoking the action, this key may be a restricted API key
* `__OW_NAMESPACE` the namespace for the _activation_ (this may not be the same as the namespace for the action)
* `__OW_ACTION_NAME` the fully qualified name of the running action
* `__OW_ACTIVATION_ID` the activation id for this running action instance
* `__OW_DEADLINE` the approximate time when this action will have consumed its entire duration quota (measured in epoch milliseconds)
