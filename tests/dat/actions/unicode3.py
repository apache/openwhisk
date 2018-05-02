# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

"""Python 3 Unicode test."""


def main(args):
    sep = args['delimiter']
    str = sep + " ☃ " + sep
    print(str)
    return {"winter": str}
