# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

def greet(dict):
    if 'name' in dict:
        name = dict['name']
    else:
        name = 'stranger'
    greeting = 'Hello ' + name + '!'
    return {'greeting': greeting}
