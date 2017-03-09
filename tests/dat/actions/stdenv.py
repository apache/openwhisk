"""Unify action container environments."""
import os


def main(dict):
    return {"auth": os.environ['__OW_API_KEY'],
            "edge": os.environ['__OW_API_HOST']}
