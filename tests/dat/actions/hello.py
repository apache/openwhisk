#!/usr/bin/env python

from __future__ import print_function


def main(args_dict):
    name = args_dict.get('name', 'stranger')
    greeting = 'Hello ' + name + '!'
    print(greeting)
    return {'greeting': greeting}


if __name__ == '__main__':
    print(main({'name': __name__}))
