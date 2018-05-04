# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

"""Python 2 Unicode test."""


def main(args):
    sep = args['delimiter']
    str = sep + " ☃ ".decode('utf-8') + sep
    print(str.encode('utf-8'))
    return {"winter": str}
