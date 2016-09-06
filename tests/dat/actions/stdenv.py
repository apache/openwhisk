import os

def main(dict):
    return { "auth": os.environ['AUTH_KEY'], "edge": os.environ['EDGE_HOST'] }
