import sys
def main(dict):
    if 'text' in dict:
        text = dict['text']
    else:
        text = "stranger"
    greeting = "Hello " + text + "!"
    print(greeting)
    return {"payload": greeting}
