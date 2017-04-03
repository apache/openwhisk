"""Python 2 Unicode test."""


def main(args):
    sep = args['delimiter']
    str = sep + " ☃ ".decode('utf-8') + sep
    print(str.encode('utf-8'))
    return {"winter": str}
