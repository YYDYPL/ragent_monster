# Ragent Monster production deployment

This directory is used to deploy the complete stack to a Docker host. The host
does not need Java or Node.js; application images are built locally and loaded
on the host.

The private `.env` file is intentionally not tracked. Copy `.env.example` to
`.env`, fill in secrets, and keep its permissions at `600`.
