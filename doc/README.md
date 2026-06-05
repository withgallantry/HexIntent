# hexdoc-hexintent

Python web book docgen and hexdoc plugin for HexIntent.
Current release version: 2.1.3.

## Setup (Windows)

```sh
py -3.11 -m venv .venv
.\.venv\Scripts\activate
pip install -e .[dev]
```

## Local usage

Create a file named `.env` in the repo root:

```sh
GITHUB_REPOSITORY=withgallantry/HexIntent
GITHUB_SHA=main
GITHUB_PAGES_URL=https://withgallantry.github.io/HexIntent
```

Run docs commands from the repo root:

```sh
hexdoc -h
hexdoc build
hexdoc merge
hexdoc serve
```

## Publish to PyPI

The Python package published from this repo is `hexdoc-hexintent`.

Use the `Build the web book` GitHub Actions workflow with:

- `release = true`
- `publish = PyPI`

The workflow publishes via GitHub OIDC trusted publishing, so the repository must be configured as a trusted publisher for the `hexdoc-hexintent` project on PyPI.

Watch mode:

```sh
npx nodemon --config doc/nodemon.json
```

## Notes

- `doc/hexdoc.toml` points at `src/main/resources`, so your existing Patchouli data is used directly.
- If your GitHub repo slug differs, update `.env` values accordingly.
