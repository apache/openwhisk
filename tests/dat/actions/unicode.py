"""Python Unicode test."""


def main(dict):
    sep = dict['delimiter']
    str = sep + " ☃ ".decode('utf-8') + sep
    print(str.encode('utf-8'))
    return {"winter": str}
