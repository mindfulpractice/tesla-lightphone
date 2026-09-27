# Tesla for Light Phone 3 — Setup Guide

Control your Tesla from your Light Phone 3. Lock, unlock, climate, trunk, and more — no smartphone needed.

Setup takes about 5 minutes. No computer required (unless you want to use the QR code option).

---

## What You'll Need

- A Light Phone 3 with the Tesla app installed
- A Tesla account (the one linked to your car)

---

## Step 1: Create a Tesla Developer App

1. Go to **[developer.tesla.com](https://developer.tesla.com)** and sign in with your Tesla account.

2. Click **Create Application** and fill in:
   - **Name**: anything (e.g., "LP3 Tesla")
   - **Description**: anything (for your reference)

3. Set these fields exactly:
   - **Allowed Origin**: `https://tesla-lightphone.app`
   - **Allowed Redirect URI**: `https://tesla-lightphone.app/setup`

4. Under **API Scopes**, select:
   - **Vehicle Information** — lets the app read your car's status (battery, temperature, location, tire pressure)
   - **Vehicle Commands** — lets the app send commands (lock/unlock, climate, trunk, sentry mode, flash lights, remote start)
   - Optionally: **Vehicle Charging Management** — lets the app manage charging (start/stop, set limits, schedule)

5. Submit and wait for approval (usually a few minutes).

6. Once approved, copy your **Client ID** and **Client Secret** from the dashboard.

### Why these URLs?

Tesla requires every developer app to list a website, but your credentials never pass through it. The URLs are like a return address on an envelope — Tesla uses them to verify the sign-in request is coming from the right app. The actual connection happens directly between your Light Phone and Tesla's servers.

### Why your own developer account?

- **Privacy** — Your Tesla credentials are never shared with anyone, not even the app developer. The sign-in happens directly between your phone and Tesla — nothing passes through any intermediary server.
- **No costs** — Tesla charges per API call, but every account gets a $10/month credit. Personal use costs well under $1/month, so the credit covers it easily.
- **Full control** — You can revoke access or delete your developer app anytime.

A shared developer account would require running a server to handle sign-ins — meaning tokens would pass through infrastructure outside your control — and the per-call API costs from all users would fall on a single account. Your own account avoids both problems.

---

## Step 2: Get Credentials to Your Phone

You have two options:

### Option A: QR Code (needs a computer)

1. Go to **[tesla-lightphone.app/setup](https://tesla-lightphone.app/setup)** on your computer.
2. Enter your Client ID and Client Secret.
3. Click **Generate QR Code**.
4. On your Light Phone, open the Tesla app and tap **SCAN QR**.
5. Point the camera at the QR code on your screen.

The QR code is generated entirely in your browser — nothing is sent to any server. You can verify this in the [source code](https://github.com/mindfulpractice/tesla-lightphone/blob/main/docs/setup.html).

**Alternative QR methods:** You can also generate the QR from your terminal:
```bash
# Mac (install first: brew install qrencode)
qrencode -o tesla-qr.png '{"t":"cred","i":"YOUR_CLIENT_ID","s":"YOUR_CLIENT_SECRET"}'
```
Scan the resulting image, then delete it.

### Option B: Manual Entry (no computer needed)

1. On your Light Phone, open the Tesla app and tap **ENTER MANUALLY**.
2. Type your Client ID and Client Secret directly.

**Note:** Some characters like uppercase I and lowercase l look the same on the Light Phone screen. Double-check these if you get an error.

---

## Step 3: Sign In

After entering credentials (by QR or manual entry), the app opens Tesla's official sign-in page directly on your Light Phone. Log in with your Tesla email and password.

This happens between your phone and Tesla — the app never sees your password.

## Step 4: Pair Key

After signing in, the app shows a key pairing screen. This enrolls the app's encryption key on your vehicle so it can send secure commands (lock, unlock, climate, etc.).

A QR code appears on screen — scan it with your smartphone's Tesla app to approve the pairing. Once confirmed on your smartphone, tap CONTINUE on the Light Phone.

**Why is this needed?** Tesla requires all third-party apps to use end-to-end encrypted commands (Vehicle Command Protocol). The key pairing authorizes the app to send encrypted commands to your specific vehicle. No server is involved — the pairing happens directly between the app and Tesla.

After pairing, you'll see a confirmation screen and you're ready to go.

---

## Troubleshooting

**"Your Client Secret appears to be incorrect"**
Double-check the secret on developer.tesla.com. Watch for uppercase I vs lowercase l — they look identical on the Light Phone screen.

**Sign-in page doesn't load**
Make sure your Light Phone has internet access. The sign-in page is hosted by Tesla, not this app.

**"Something went wrong connecting to Tesla"**
Try signing in again. If it keeps failing, verify your Allowed Origin and Redirect URI in your Tesla developer app match exactly: `https://tesla-lightphone.app` and `https://tesla-lightphone.app/setup`.

**Commands fail with 412 error**
The app automatically attempts partner registration during setup. If it fails, commands requiring VCP (like lock/unlock) won't work until registration succeeds. Try disconnecting and reconnecting.

**"Vehicle asleep — try again"**
Normal. Send any command and the car wakes up automatically (takes a few seconds).

---

## Privacy & Security

- **All credentials stay on your device.** The app only talks to Tesla's servers.
- **No analytics, no tracking, no background activity.**
- **Auth tokens are stored in the app's private storage** on the Light Phone, inaccessible to other apps.
- **The setup website is static HTML + JavaScript** — no backend, no database, no server-side code. [Source code](https://github.com/mindfulpractice/tesla-lightphone).
- **Open source** — you can read every line.

---

## Disconnecting

Go to Settings in the app and tap Disconnect. This clears all tokens and credentials from the app. Your Tesla developer app on developer.tesla.com remains — delete it there if you want to fully revoke access.

---

## More Questions

See the full **[FAQ page](https://tesla-lightphone.app/faq)** for answers about privacy, API scopes, QR safety, and more.

---

## Cost

Tesla charges per API call (roughly $0.001 per command, $0.002 per data request, $0.02 per wake-up), but every developer account gets a **$10/month credit** applied automatically. Normal personal use costs well under $1/month, so the credit covers it easily. No payment method is required to get started.
