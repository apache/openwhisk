# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.imitations under the License.

def main(dict):
    if 'text' in dict:
        text = dict['text']
    else:
        text = "stranger"
    greeting = "Hello " + text + "!"
    print(greeting)
    return {"payload": greeting}
