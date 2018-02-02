# Working with parameters

### Passing parameters to an action at invoke time

Parameters can be passed to the action when it is invoked.  These examples use JavaScript but all the other languages work the same way.

1. Use parameters in the action. For example, update the 'hello.js' file with the following content:

  ```javascript
  function main(params) {
      return {payload:  'Hello, ' + params.name + ' from ' + params.place};
  }
  ```

  The input parameters are passed as a JSON object parameter to the `main` function. Notice how the `name` and `place` parameters are retrieved from the `params` object in this example.

2. Update the action so it is ready to use:

  ```
  wsk action update hello hello.js
  ```

3.  Parameters can be provided explicitly on the command-line, or by supplying a file containing the desired parameters

  To pass parameters directly through the command-line, supply a key/value pair to the `--param` flag:
  ```
  wsk action invoke --result hello --param name Dorothy --param place Kansas
  ```

  In order to use a file containing parameter content, create a file containing the parameters in JSON format. The
  filename must then be passed to the `param-file` flag:

  Example parameter file called parameters.json:
  ```json
  {
      "name": "Dorothy",
      "place": "Kansas"
  }
  ```

  ```
  wsk action invoke --result hello --param-file parameters.json
  ```

  ```json
  {
      "payload": "Hello, Dorothy from Kansas"
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
  wsk action invoke --result hello -p person '{"name": "Dorothy", "place": "Kansas"}'
  ```

  The result is the same because the CLI automatically parses the `person` parameter value into the structured object that the action now expects:
  ```json
  {
      "payload": "Hello, Dorothy from Kansas"
  }
  ```

### Setting default parameters on an action

Actions can be invoked with multiple named parameters. Recall that the `hello` action from the previous example expects two parameters: the *name* of a person, and the *place* where they're from.

Rather than pass all the parameters to an action every time, you can bind certain parameters. The following example binds the *place* parameter so that the action defaults to the place "Kansas":

1. Update the action by using the `--param` option to bind parameter values, or by passing a file that contains the parameters to `--param-file`

  To specify default parameters explicitly on the command-line, provide a key/value pair to the `param` flag:

  ```
  wsk action update hello --param place Kansas
  ```

  Passing parameters from a file requires the creation of a file containing the desired content in JSON format.
  The filename must then be passed to the `-param-file` flag:

  Example parameter file called parameters.json:
  ```json
  {
      "place": "Kansas"
  }
  ```

  ```
  wsk action update hello --param-file parameters.json
  ```

2. Invoke the action, passing only the `name` parameter this time.

  ```
  wsk action invoke --result hello --param name Dorothy
  ```
  ```json
  {
      "payload": "Hello, Dorothy from Kansas"
  }
  ```

  Notice that you did not need to specify the place parameter when you invoked the action. Bound parameters can still be overwritten by specifying the parameter value at invocation time.

3. Invoke the action, passing both `name` and `place` values. The latter overwrites the value that is bound to the action.

  Using the `--param` flag:

  ```
  wsk action invoke --result hello --param name Dorothy --param place "Washington, DC"
  ```

  Using the `--param-file` flag:

  File parameters.json:
  ```json
  {
    "name": "Dorothy",
    "place": "Washington, DC"
  }
  ```

  ```
  wsk action invoke --result hello --param-file parameters.json
  ```

  ```json
  {  
      "payload": "Hello, Dorothy from Washington, DC"
  }
  ```

### Setting default parameters on a package

Parameters can be set at the package level, and these will serve as default parameters for actions unless:

- the action itself has a default parameter
- the action has a parameter supplied at invoke time, which will always be the "winner" where more than one parameter is available

The following example sets a default parameter of `name` on the `MyApp` package and shows an action making use of it.

1. Create a package with a parameter set

 ```
 wsk package update MyApp --param name World
 ```

2. Create an action in this package

 ```
    function main(params) {
        return {payload: "Hello, " + params.name};
    }
 ```

 ```
 wsk action update MyApp/hello hello.js
 ```

3. Invoke the action, and observe the default package parameter in use
 ```
 wsk action invoke --result MyApp/hello
 ```

 ```
    {
        "payload": "Hello, World"
    }
 ```
