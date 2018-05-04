// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

/* Swift action with a non-default entry point. */
func niam(args: [String:Any]) -> [String:Any] {
    return [ "greetings" : "Hello from a non-standard entrypoint." ]
}
