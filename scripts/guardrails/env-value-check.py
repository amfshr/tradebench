#!/usr/bin/env python3
"""G1's value check (E0-T4): no value from a local env file appears in what is about to be committed.

Everything in .env* is personal by definition (G1), and no pattern can tell a personal URL or account id from a
synthetic one — so this compares against the values themselves. It reads the env files on this machine only and
reports a leaked variable by its NAME, never its value.

  env-value-check.py --staged      the staged changes (the pre-commit hook)
  env-value-check.py --history     every commit's changes, every branch (the one-off sweep)
  env-value-check.py PATH...       files

Env files: TRADEBENCH_ENV_FILES (colon-separated) if set, else <repo>/.env and ~/.config/tradebench/*.env.
A value that .env.example also holds is public (a published default or example), and values under 5 characters
are too short to mean anything; both are skipped.
"""
import glob
import os
import subprocess
import sys

MIN_LENGTH = 5


def repo_root():
    return subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True,
                          check=True).stdout.strip()


def parse(path):
    values = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[len("export "):]
            name, value = line.split("=", 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                value = value[1:-1]
            values[name.strip()] = value
    return values


def secrets(root):
    listed = os.environ.get("TRADEBENCH_ENV_FILES")
    files = listed.split(":") if listed else [os.path.join(root, ".env")] + sorted(
        glob.glob(os.path.expanduser("~/.config/tradebench/*.env")))
    example = os.path.join(root, ".env.example")
    public = set(parse(example).values()) if os.path.exists(example) else set()
    found = {}
    for path in files:
        if os.path.isfile(path):
            for name, value in parse(path).items():
                if len(value) >= MIN_LENGTH and value not in public:
                    found.setdefault(value, (name, os.path.basename(path)))
    return found


def git_lines(args):
    out = subprocess.run(["git"] + args, capture_output=True, text=True, errors="replace", check=True).stdout
    return out.splitlines()


def staged():
    """Yields (where, text) for each added line and each staged path."""
    where = "staged changes"
    for line in git_lines(["diff", "--cached", "--no-color", "--unified=0", "--no-ext-diff"]):
        if line.startswith("+++ b/"):
            where = line[len("+++ b/"):]
            yield where, where
        elif line.startswith("+") and not line.startswith("+++"):
            yield where, line[1:]


def history():
    commit, where = "?", "?"
    for line in git_lines(["log", "--all", "-p", "--no-color", "--unified=0", "--no-ext-diff", "--format=commit %H%n%B"]):
        if line.startswith("commit ") and len(line) == 47:
            commit = line[7:14]
            where = commit + " (message)"
        elif line.startswith("+++ b/"):
            where = commit + " " + line[len("+++ b/"):]
            yield where, where
        elif line.startswith("+") and not line.startswith("+++"):
            yield where, line[1:]
        elif where.endswith("(message)"):
            yield where, line


def files(paths):
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                yield path, line


def main(argv):
    if not argv:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    root = repo_root()
    values = secrets(root)
    source = staged() if argv == ["--staged"] else history() if argv == ["--history"] else files(argv)
    leaks = set()
    for where, text in source:
        for value, (name, env_file) in values.items():
            if value in text:
                leaks.add((name, env_file, where))
    for name, env_file, where in sorted(leaks):
        print(f"env-value-check: {name} (from {env_file}) appears in {where}", file=sys.stderr)
    if leaks:
        print("env-value-check: refused — everything in an env file is personal (G1); use a synthetic value of the"
              " same shape", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
