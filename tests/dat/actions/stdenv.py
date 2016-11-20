import os

def main(dict):
    return { "auth": os.environ['__OW_APIKEY'], "edge": os.environ['__OW_APIHOST'] }
