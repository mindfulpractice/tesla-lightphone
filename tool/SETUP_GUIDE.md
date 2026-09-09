# Tesla Tool for Light Phone 3 — Setup Guide

Control your Tesla from your Light Phone 3. Lock, unlock, climate, trunk, and more — no smartphone needed.

This guide covers everything from creating your Tesla developer account to scanning the QR code that connects your phone. You'll need a computer and about 20 minutes. After setup, the app works independently.

---

## What You'll Need

- A Light Phone 3 with USB debugging enabled ([modding guide](https://www.reddit.com/r/ModifiedLightPhones/comments/1v1wvwr/updated_light_phone_iii_modding_guide/)) and USB cable
- A computer (Mac, Windows, or Linux)
- A Tesla account (the one linked to your car)
- A GitHub account (free — github.com)
- JDK 17 installed (for building the app)
- Android SDK / platform-tools installed (for `adb`)

---

## Step 1: Create a Tesla Developer Account

1. Go to **developer.tesla.com** and sign in with your Tesla account.

2. Click **Create Application** and fill in:
   - **Name**: anything (e.g., "LP3 Tesla")
   - **Description**: "Light Phone 3 Tesla control"
   - **Purpose**: "Personal vehicle control"
   - **Allowed Origin**: `https://YOUR-GITHUB-USERNAME.github.io`
   - **Allowed Redirect**: `https://YOUR-GITHUB-USERNAME.github.io/tesla-lightphone/tesla-auth/`

3. Save your **Client ID** and **Client Secret** somewhere safe. You'll need both shortly. Never share the Client Secret publicly.

4. **Billing is optional.** Tesla gives a $10/month discount that covers typical personal use. If you don't add a payment method, commands will simply stop working if you ever exceed $10 in a month (unlikely with normal use). You can optionally add a payment method and set a billing limit in the developer dashboard.

---

## Step 2: Set Up GitHub Pages

Your app needs a small website to host two things: a public key (required by Tesla) and an auth page (generates the QR code you'll scan).

1. **Fork this repository** on GitHub (or clone and push to your own repo).

2. In your fork's **Settings → Pages**, enable GitHub Pages:
   - Source: "Deploy from a branch"
   - Branch: `main`, folder: `/docs`
   - Save

3. Create the auth page config:
   - Go to `docs/tesla-auth/config.js.example` in your fork
   - Copy it to `docs/tesla-auth/config.js`
   - Replace `your-client-id-here` with your actual Client ID from Step 1

4. Create the public key placeholder:
   - In the `docs/` folder, create the path: `.well-known/appspecific/com.tesla.3p.public-key.pem`
   - Leave the file empty for now — you'll fill it in after the first app launch

5. Push your changes and wait a minute for GitHub Pages to deploy. Verify by visiting:
   `https://YOUR-GITHUB-USERNAME.github.io/tesla-lightphone/tesla-auth/`
   You should see the "Tesla Connect" auth page.

---

## Step 3: Configure and Build the App

1. Open `local.properties` in the project root (this file is gitignored — your secrets stay local):

   ```
   TESLA_CLIENT_ID=your-client-id-from-step-1
   TESLA_CLIENT_SECRET=your-client-secret-from-step-1
   TESLA_REDIRECT_URI=https://YOUR-GITHUB-USERNAME.github.io/tesla-lightphone/tesla-auth/
   ```

2. Connect your Light Phone 3 via USB (USB debugging should already be enabled from the prerequisites).

3. Build and install:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # Mac only
   cd path/to/light-sdk
   ./gradlew :tool:installDebug
   ```

   The Tesla tool should appear on your Light Phone.

---

## Step 4: Get Your Public Key onto GitHub

The app generates a unique key pair on first launch. Tesla requires the public half hosted on your website.

1. Open the Tesla tool on your Light Phone once (it will show the setup screen — that's fine, just let it generate the keys).

2. Retrieve the public key from the phone:

   ```bash
   adb shell run-as com.thelightphone.tool.tesla cat files/tesla_public_key.pem
   ```

   If you have multiple devices connected, target the LP3:
   ```bash
   adb -s YOUR_DEVICE_SERIAL shell run-as com.thelightphone.tool.tesla cat files/tesla_public_key.pem
   ```

3. Copy the entire output (from `-----BEGIN PUBLIC KEY-----` to `-----END PUBLIC KEY-----`).

4. In your GitHub repo, edit `docs/.well-known/appspecific/com.tesla.3p.public-key.pem` and paste the public key. Commit and push.

5. Wait a minute, then verify by visiting:
   `https://YOUR-GITHUB-USERNAME.github.io/tesla-lightphone/.well-known/appspecific/com.tesla.3p.public-key.pem`
   It should show your public key.

---

## Step 5: Register with Tesla

These one-time terminal commands register your app with Tesla's servers.

1. **Get a partner token:**

   ```bash
   curl --request POST \
     --header 'Content-Type: application/x-www-form-urlencoded' \
     --data-urlencode 'grant_type=client_credentials' \
     --data-urlencode 'client_id=YOUR_CLIENT_ID' \
     --data-urlencode 'client_secret=YOUR_CLIENT_SECRET' \
     --data-urlencode 'scope=openid vehicle_device_data vehicle_cmds' \
     --data-urlencode 'audience=https://fleet-api.prd.na.vn.cloud.tesla.com' \
     'https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token'
   ```

   Copy the `access_token` from the response.

2. **Register your domain:**

   ```bash
   curl --request POST \
     --header 'Authorization: Bearer YOUR_PARTNER_TOKEN' \
     --header 'Content-Type: application/json' \
     --data '{"domain":"YOUR-GITHUB-USERNAME.github.io"}' \
     'https://fleet-api.prd.na.vn.cloud.tesla.com/api/1/partner_accounts'
   ```

   You should see a success response with your public key echoed back.

---

## Step 6: Pair Your Car's Virtual Key

This lets the app send commands to your car. You only do this once per vehicle.

1. Open this URL in a browser (phone or computer):
   `https://tesla.com/_ak/YOUR-GITHUB-USERNAME.github.io`

2. Sign in with your Tesla account if prompted.

3. The Tesla app on your smartphone will ask you to approve a virtual key. **Tap your Tesla key card on your phone** to confirm.

---

## Step 7: Connect the App

1. On your computer, go to your auth page:
   `https://YOUR-GITHUB-USERNAME.github.io/tesla-lightphone/tesla-auth/`

2. Click **Sign in with Tesla** and log in with your Tesla account.

3. After signing in, you'll see a QR code.

4. On your Light Phone, open the Tesla tool and tap **SCAN QR CODE**.

5. Scan the QR code. The app will connect to your Tesla account.

6. You should see your vehicle's controls. Done!

---

## Troubleshooting

**Build fails with JdkImageTransform error**
Make sure you're using JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`

**"adb: command not found"**
Use the full path: `~/Library/Android/sdk/platform-tools/adb`

**Commands fail with 412 error**
Your partner registration (Step 5) didn't complete. Re-run those curl commands.

**"Vehicle asleep — try again"**
Normal. Send any command and the car wakes up automatically (takes a few seconds).

**Camera permission error on QR scan**
Go back and try again. If it persists, restart the Light Phone.

**"Session expired"**
Go to your auth page, sign in again, scan a new QR code. Your virtual key is still paired — you just need a fresh auth token.

---

## Privacy & Security

- **All credentials stay on your device.** The app only talks to Tesla's servers.
- **No analytics, no tracking, no background activity.**
- **Your Client Secret is compiled into the app** on your phone — never push it to GitHub. The `local.properties` file is gitignored by default.
- **Your GitHub repo only contains your public key**, which is safe to share by design (it's the "public" half of a key pair).
- **Auth tokens are stored in the app's private storage** on the Light Phone, inaccessible to other apps.

---

## Cost

Tesla gives developers a $10/month discount that covers typical personal use. You don't need to add a payment method — if usage ever exceeds $10 in a month, commands simply stop working until the next billing cycle. You can optionally add a payment method and set a billing limit in the developer dashboard.
