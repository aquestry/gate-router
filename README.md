# Gate Router

Gate Router is a small Kotlin web panel to manage Gate Lite routes for your Dockerized Minecraft servers.

## Features

- Simple web UI for Gate Lite routes
- Reads and writes Gate’s `config.yml` in the required structure
- Lists Docker containers and lets you pick a backend (`<container-name>:<port>`)
- Per-host notes stored in a separate YAML file
- Password-only auth (from env), with basic rate limiting on login

## How it works

- On startup the app loads `CONFIG_PATH` (default `/app/config.yml`) into memory and shows existing routes in the dashboard.
- When you add or delete a host, the app writes back to `config.yml` in Gate’s expected format and prints a log hint that Gate should reload (Gate’s own auto-reload can watch the file).
- Backends are saved as `<docker-container-name>:<port>` so Gate can resolve them via Docker’s internal DNS.
- Notes are stored by domain in a separate YAML file at `NOTES_PATH`.
