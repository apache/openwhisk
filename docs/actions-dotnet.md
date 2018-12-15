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

## Creating and invoking .NET Core actions

The following sections guide you through creating and invoking a single .NET Core action.

In order to compile, test and archive .NET Core projects, you must have the [.NET Core SDK](https://www.microsoft.com/net/download) installed locally and the environment variable `DOTNET_HOME` set to the location where the `dotnet` executable can be found.

A .NET Core action is a .NET Core class library with a method called `Main` that has the exact signature as follows:

```csharp
public Newtonsoft.Json.Linq.JObject Main(Newtonsoft.Json.Linq.JObject);
```

For example, create a C# project called `Apache.OpenWhisk.Example.Dotnet`:

```bash
dotnet new classlib -n Apache.OpenWhisk.Example.Dotnet -lang "C#"
cd Apache.OpenWhisk.Example.Dotnet
```

Install the [Newtonsoft.Json](https://www.newtonsoft.com/json) NuGet package as follows:

```bash
dotnet add package Newtonsoft.Json -v 12.0.1
```

Now create a file called `Hello.cs` with the following content:

```csharp
using System;
using Newtonsoft.Json.Linq;

namespace Apache.OpenWhisk.Example.Dotnet
{
    public class Hello
    {
        public JObject Main(JObject args)
        {
            string name = "stranger";
            if (args.ContainsKey("name")) {
                name = args["name"].ToString();
            }
            JObject message = new JObject();
            message.Add("greeting", new JValue($"Hello, {name}!"));
            return (message);
        }
    }
}
```

Publish the project as follows:

```bash
dotnet publish -c Release -o out
```

Zip the published files as follows:

```bash
cd out
zip -r -0 helloDotNet.zip *
```

### Create the .NET Core Action

You need to specify the name of the function handler using `--main` argument.
The value for `main` needs to be in the following format:
`{Assembly}::{Class Full Name}::{Method}`, e.q.,
`Apache.OpenWhisk.Example.Dotnet::Apache.OpenWhisk.Example.Dotnet.Hello::Main`

To use on a deployment of OpenWhisk that contains the runtime as a kind:

```bash
wsk action update helloDotNet helloDotNet.zip --main Apache.OpenWhisk.Example.Dotnet::Apache.OpenWhisk.Example.Dotnet.Hello::Main --kind dotnet:2.2
```

### Invoke the .NET Core Action

Action invocation is the same for .NET Core actions as it is for Swift and JavaScript actions:

```bash
wsk action invoke --result helloDotNet --param name World
```

```json
  {
      "greeting": "Hello World!"
  }
```
