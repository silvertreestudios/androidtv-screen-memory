"""Pure release identity validation; no network or signing operations."""
import re


def identity(sha, run_number):
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Expected full lowercase commit SHA')
    if not re.fullmatch(r'[1-9][0-9]*', str(run_number)):
        raise ValueError('Expected positive run number')
    code = int(run_number) + 3
    if code > 2100000000:
        raise ValueError('Android versionCode exhausted')
    return code, '1.2+sha.' + sha


def trusted_main(event, ref):
    return event == 'push' and ref == 'refs/heads/main'
