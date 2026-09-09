# Tesla for Light Phone 3

Control your Tesla from your Light Phone 3. Lock, unlock, climate, trunk, sentry mode, and more — no smartphone needed.

## Quick Start

See the full **[Setup Guide](tool/SETUP_GUIDE.md)** for step-by-step instructions covering everything from Tesla developer account creation to scanning the QR code.

## What This Is

This is a Tesla remote control tool built for the Light Phone 3 using the [Light SDK](https://github.com/lightphone/light-sdk). It uses Tesla's Fleet API with end-to-end encrypted vehicle commands (VCP) — your credentials never leave your device.

## Repo Structure

You only need to care about two folders:

- **`tool/`** — The Tesla tool source code (this is the app)
- **`docs/tesla-auth/`** — The auth page you'll host on GitHub Pages for login

Everything else (`sdk/`, `plugin/`, `gradle/`, etc.) is the Light SDK build system. Don't modify those files — they're needed to compile the tool but you never touch them.

## Features

- Lock / Unlock
- Climate on/off, defrost, overheat protection
- Open/close trunk and frunk
- Charge port open/close
- Sentry mode on/off
- Flash lights, honk horn
- Vent/close windows
- Remote start (2-minute drive window)
- Real-time vehicle status (battery, temperature, location)

## Privacy & Security

- All credentials stay on your device
- Each user creates their own Tesla developer account — no shared credentials
- End-to-end encrypted commands via Tesla VCP
- No analytics, no tracking, no background activity
- Open source — you can read every line

## Cost

Tesla gives a $10/month discount that covers typical personal use. No payment method required — if you exceed $10 in a month, commands simply stop until the next cycle.

## Credits

Built on the [Light SDK](https://github.com/lightphone/light-sdk) by The Light Phone, Inc.
