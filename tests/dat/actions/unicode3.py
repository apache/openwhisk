"""Python 3 Unicode test."""


def main(args):
    sep = args['delimiter']
    str = sep + " ☃ " + sep
    print(str)
    return {"winter": str}
