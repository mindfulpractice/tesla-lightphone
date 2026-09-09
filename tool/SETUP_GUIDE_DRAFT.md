# Tesla Tool for Light Phone 3 — Setup Guide

This guide walks you through setting up the Tesla tool on your Light Phone 3. You'll need a computer and about 15-20 minutes. After setup, the app works independently — no computer needed.

---

## What You'll Need

- A Light Phone 3 with USB cable
- A computer (Mac, Windows, or Linux)
- A Tesla account (the one linked to your car)
- A GitHub account (free — github.com)
- Android build tools installed (Android SDK + JDK 17)

---

## Part 1: Tesla Developer Account (5 minutes)

1. Go to **developer.tesla.com** and sign in with your Tesla account.

2. Click **Create Application** and fill in:
   - **Name**: anything you like (e.g., "LP3 Tesla")
   - **Description**: "Light Phone 3 Tesla control tool"
   - **Purpose**: "Personal vehicle control"
   - **Allowed Origin**: `https://YOUR-GITHUB-USERNAME.github.io`
   - **Allowed Redirect**: `https://YOUR-GITHUB-USERNAME.github.io/auth/callback`

3. After creating the app, you'll see your **Client ID** and **Client Secret**. Save both somewhere safe — you'll need them later. Never share the Client Secret publicly.

4. Add a payment method in the billing section. Tesla gives a $10/month discount which covers personal use (about 100 commands per day for 2 vehicles). Set a billing limit (e.g., $10) to prevent surprises.

---

## Part 2: Host Your Public Key (5 minutes)

The app generates a unique key pair on your phone. Tesla requires the public half to be hosted on a website you control. GitHub Pages makes this free and easy.

1. Go to **github.com** and create a new repository named exactly:
   `YOUR-GITHUB-USERNAME.github.io`
   (This becomes your free GitHub Pages site.)

2. In the repository, create the following folder structure and file:
   `.well-known/appspecific/com.tesla.3p.public-key.pem`
   (Leave the file empty for now — you'll paste your public key here after the first app launch.)

3. Go to **Settings → Pages** in the repository and make sure GitHub Pages is enabled (deploy from the `main` branch).

4. Verify it works by visiting:
   `https://YOUR-GITHUB-USERNAME.github.io/.well-known/appspecific/com.tesla.3p.public-key.pem`
   (It should show a blank page — that's fine for now.)

---

## Part 3: Build and Install the App (5 minutes)

1. Download or clone the Tesla tool source code.

2. Open the file `tool/src/main/kotlin/.../TeslaApi.kt` and replace the placeholder credentials with your own:
   - `CLIENT_ID` → your Client ID from Part 1
   - `CLIENT_SECRET` → your Client Secret from Part 1
   - Update the domain references to `YOUR-GITHUB-USERNAME.github.io`

3. Connect your Light Phone 3 via USB and enable developer mode if you haven't already.

4. Open a terminal and run:
   ```
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   cd path/to/light-sdk
   ./gradlew :tool:installDebug
   ```

5. The app should appear on your Light Phone.

---

## Part 4: Get Your Public Key (2 minutes)

1. Open the Tesla tool on your Light Phone. It will automatically generate a key pair on first launch.

2. To retrieve the public key from your phone, run on your computer:
   ```
   adb shell run-as com.thelightphone.tool.tesla cat files/tesla_public_key.pem
   ```

3. Copy the output (everything from `-----BEGIN PUBLIC KEY-----` to `-----END PUBLIC KEY-----`).

4. Go to your GitHub repository and edit the file:
   `.well-known/appspecific/com.tesla.3p.public-key.pem`
   Paste the public key and commit.

5. Wait a minute for GitHub Pages to update, then verify by visiting the URL from Part 2 — it should now show your public key.

---

## Part 5: Register with Tesla (3 minutes)

These terminal commands register your app with Tesla's servers. You only need to do this once.

1. Get a partner token:
   ```
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

2. Register your domain:
   ```
   curl --request POST \
     --header 'Authorization: Bearer YOUR_PARTNER_TOKEN' \
     --header 'Content-Type: application/json' \
     --data '{"domain":"YOUR-GITHUB-USERNAME.github.io"}' \
     'https://fleet-api.prd.na.vn.cloud.tesla.com/api/1/partner_accounts'
   ```
   You should see a success response.

---

## Part 6: Approve the Virtual Key (1 minute)

This lets your app send commands to your car. You only do this once.

1. On your phone or computer, open:
   `https://tesla.com/_ak/YOUR-GITHUB-USERNAME.github.io`

2. Sign in with your Tesla account if prompted.

3. The Tesla app on your smartphone will ask you to approve a virtual key. Tap your key card on your phone to confirm. (You need your Tesla key card for this step.)

---

## Part 7: Connect the App (1 minute)

1. You'll need a QR code containing your Tesla auth credentials. Generate one using the auth page (see the project README for details on setting up the auth page on your GitHub site).

2. Open the Tesla tool on your Light Phone and tap **SCAN QR CODE**.

3. Scan the QR code. The app will connect to your Tesla account.

4. You should see your vehicle's controls. You're done!

---

## Troubleshooting

**"Vehicle asleep — try again in a moment"**
Your car is in sleep mode. Send any command and it will wake up automatically.

**Camera permission error on QR scan**
Go back and try again. If it persists, restart the Light Phone.

**"Session expired" after bad WiFi**
The app will show the setup screen. Tap SCAN QR CODE to reconnect — your virtual key is still paired, you just need to refresh the auth token.

**Commands fail with 412 error**
Your partner registration (Part 5) didn't complete. Re-run those steps.

**Build fails with JdkImageTransform error**
Make sure you're using JDK 17, not a newer version. Run:
`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`

---

## Privacy

- All credentials stay on your device. No data is sent anywhere except Tesla's own servers.
- The app has no analytics, no tracking, and no background activity.
- Your GitHub repository only contains your public key, which is safe to share by design.
- Your Client Secret is compiled into the app on your phone — never push it to GitHub.

---

## Cost

Tesla charges per API use. You get a $10/month discount which covers typical personal use. Set a billing limit in your Tesla developer dashboard to cap costs.
