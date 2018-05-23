# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

"""Unify action container environments."""
import os


def main(dict):
    return {"auth": os.environ['__OW_API_KEY'],
            "edge": os.environ['__OW_API_HOST']}
