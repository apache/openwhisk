import sys
def main(dict):
    if 'user' in dict:
        print("hello, " + dict['user'] + "!")
        return {"user":dict['user']}
    else:
        print("hello world!")
        return {"user":"world"}

