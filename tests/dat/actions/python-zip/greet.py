def greet(dict):
    if 'name' in dict:
        name = dict['name']
    else:
        name = 'stranger'
    greeting = 'Hello ' + name + '!'
    return {'greeting': greeting}
