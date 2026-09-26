# Tesla Control v2 — In-App Setup Design

## Goal

Eliminate the need to build from source. Users download a single APK, and all setup happens within the app. The current tool (`tool/`) stays untouched as the working backup.

## What Changes

| Current (v1) | Proposed (v2) |
|---|---|
| Credentials baked in at build time via `local.properties` | User enters CLIENT_ID + CLIENT_SECRET in a setup screen, stored encrypted on device |
| Auth via external web page + QR code scan | Auth via in-app WebView — no QR needed |
| Public key hosted on user's GitHub Pages | Public key uploaded to a shared key service |
| Partner registration via manual curl commands | App does partner registration automatically |
| Virtual key pairing via manual URL | App opens the pairing URL, user approves in Tesla app |
| Must build from source | Download and install a single APK |

## Architecture

### New module: `tool-v2/`

A separate tool module alongside `tool/`. Same Tesla API logic, different setup flow. Both can be installed on the same phone (different package name).

```
light-sdk/
├── tool/          ← Current app (unchanged, your backup)
├── tool-v2/       ← New app with in-app setup
├── sdk/
├── plugin/
└── ...
```

`tool-v2` package name: `com.thelightphone.tool.teslasetup` (or similar, must differ from `com.thelightphone.tool.tesla` so both can coexist).

### Public Key Service

A tiny serverless function (Cloudflare Worker or Vercel Edge Function) that does two things:

1. **PUT** `/keys/{user-id}/com.tesla.3p.public-key.pem` — stores a public key
2. **GET** `/.well-known/appspecific/com.tesla.3p.public-key.pem` — serves the key for a given domain

**Important:** This service only handles **public** keys. Public keys are designed to be shared — that's the whole point. No credentials, tokens, or secrets ever touch this service.

**Domain setup:** You register one domain (e.g., `tesla-lightphone.app` or a subdomain) and point it at the service. Each user's key is stored under a unique ID but served at the Tesla-required `.well-known` path.

**How it works with Tesla's domain model:**

Tesla expects one public key per registered domain. Two options:

- **Option A — Shared domain, single key pair:** The service holds one key pair. All users share it. The private key ships inside the APK (encrypted/obfuscated). Simpler, but the private key is extractable by a determined user. Low real-world risk — the key only signs commands to cars the user has already authenticated with.

- **Option B — Per-user subdomains:** Each user gets `{user-id}.tesla-lightphone.app`. The service provisions a wildcard subdomain and stores each user's unique public key. More secure (each user has their own private key on-device), but requires wildcard DNS + per-user partner registration with Tesla.

**Recommendation:** Start with **Option A** (shared key pair). It's dramatically simpler to build and maintain. The private key in the APK only lets someone sign commands to their own car (they still need OAuth tokens). If security concerns arise, migrate to Option B later without changing the app's user-facing flow.

### Credential Storage

CLIENT_ID and CLIENT_SECRET are entered by the user and stored in Android's `EncryptedSharedPreferences` (backed by Android Keystore hardware). Never leaves the device, never sent to any service except Tesla's own API endpoints.

### Code Changes from v1

Most of the Tesla API logic (`TeslaApi.kt`, `VcpSession.kt`, `ProtobufEncoder.kt`, `VcpMessages.kt`, `TeslaKeystore.kt`, `TeslaTokenCipher.kt`, `TokenStore.kt`) can be shared or copied. Key changes:

1. **`TeslaApi.kt`** — `CLIENT_ID`, `CLIENT_SECRET`, `REDIRECT_URI` read from `EncryptedSharedPreferences` instead of `BuildConfig`
2. **`TokenSetupScreen.kt`** → replaced by `SetupWizardScreen.kt` (multi-step setup flow)
3. **New: `SetupWizardScreen.kt`** — the guided setup (see flow below)
4. **New: `CredentialStore.kt`** — read/write CLIENT_ID/SECRET to EncryptedSharedPreferences
5. **New: `KeyServiceClient.kt`** — uploads public key to the key service
6. **New: `PartnerRegistration.kt`** — handles the partner token + domain registration API calls
7. **`TokenSetupViewModel.kt`** → replaced by `SetupWizardViewModel.kt`

## In-App Setup Flow

### Screen 1: Welcome
```
Tesla Control Setup

This app lets you control your Tesla
from your Light Phone.

You'll need:
• A Tesla developer account (free)
• Your car nearby for key pairing

Takes about 10 minutes.

        [ Get Started ]
```

### Screen 2: Developer Account
```
Step 1 of 5

Create a Tesla Developer Account

Go to developer.tesla.com and create
an application.

Set the Allowed Origin to:
  https://tesla-lightphone.app

Set the Allowed Redirect to:
  https://tesla-lightphone.app/auth

Copy your Client ID and Client Secret.

      [ I have my credentials ]
```

### Screen 3: Enter Credentials
```
Step 2 of 5

Enter Your Credentials

Client ID
[ ________________________________ ]

Client Secret
[ ________________________________ ]

These stay on your device. They are
never sent to anyone except Tesla.

             [ Save ]
```

### Screen 4: Registration (Automatic)
```
Step 3 of 5

Registering with Tesla...

✓ Uploading public key
✓ Getting partner token
⟳ Registering domain...

This takes a few seconds.
```

The app:
1. Generates key pair (or reuses existing)
2. Uploads public key to the key service
3. Gets a partner token from Tesla using CLIENT_ID + CLIENT_SECRET
4. Registers the domain with Tesla using the partner token

### Screen 5: Pair Virtual Key
```
Step 4 of 5

Pair Your Car

Tap the button below. Your Tesla
smartphone app will ask you to
approve a virtual key.

       [ Pair Vehicle ]

Make sure you're near your car and
have the Tesla app on your smartphone.
```

Opens `https://tesla.com/_ak/tesla-lightphone.app` in a browser. User approves in their Tesla smartphone app.

### Screen 6: Sign In
```
Step 5 of 5

Sign In with Tesla
```

In-app WebView loads Tesla OAuth. After auth completes, the app captures the auth code and exchanges it for tokens (using CLIENT_SECRET stored on device). No QR code needed.

### Screen 7: Done
```
Setup Complete!

Your Tesla is connected.

       [ Go to Controls ]
```

## Key Service Spec (Option A — Shared Key)

**Endpoint:** `https://tesla-lightphone.app`

**Routes:**
- `GET /.well-known/appspecific/com.tesla.3p.public-key.pem` → returns the shared public key (static file)

That's it for Option A. No database, no API, no uploads. Just a static file served at the right path. The shared private key ships inside the APK (encrypted with a hardcoded key, obfuscated).

**Hosting:** Cloudflare Pages (free), Vercel (free), or GitHub Pages on a custom domain. Total cost: ~$12/year for the domain.

## Billing / Cost Model

Same as v1 — each user creates their own Tesla developer account and gets the $10/month discount. No payment method required. The key service costs you ~$12/year for the domain. No per-user costs to you.

## Security Summary

| What | Where | Who can see it |
|---|---|---|
| CLIENT_ID | User's device (EncryptedSharedPreferences) | Only the user |
| CLIENT_SECRET | User's device (EncryptedSharedPreferences) | Only the user |
| OAuth tokens | User's device (app private storage) | Only the user |
| Private key (Option A) | Inside APK (encrypted/obfuscated) | Extractable with effort |
| Public key | Key service (public URL) | Anyone (by design) |

**You (the developer) never see any user's credentials.** The key service only handles public keys.

## File Structure for v2

```
tool-v2/
├── build.gradle.kts              ← New module config (no BuildConfig secrets)
├── src/main/
│   ├── AndroidManifest.xml
│   └── kotlin/com/thelightphone/tool/teslav2/
│       ├── TeslaApi.kt           ← Modified: reads credentials from CredentialStore
│       ├── CredentialStore.kt    ← NEW: EncryptedSharedPreferences wrapper
│       ├── SetupWizardScreen.kt  ← NEW: Multi-step setup UI
│       ├── SetupWizardViewModel.kt ← NEW: Setup logic
│       ├── PartnerRegistration.kt ← NEW: Automated partner registration
│       ├── HomeScreen.kt         ← Copied from v1
│       ├── HomeScreenViewModel.kt ← Copied from v1
│       ├── SettingsScreen.kt     ← Copied from v1
│       ├── SettingsViewModel.kt  ← Copied from v1
│       ├── ControlConfig.kt      ← Copied from v1
│       ├── VcpSession.kt         ← Copied from v1
│       ├── VcpMessages.kt        ← Copied from v1
│       ├── ProtobufEncoder.kt    ← Copied from v1
│       ├── TeslaKeystore.kt      ← Copied from v1
│       ├── TeslaTokenCipher.kt   ← Copied from v1
│       └── TokenStore.kt         ← Copied from v1
```

## Migration Path

1. Build and test v2 on your phone alongside v1
2. If v2 works, distribute the APK (GitHub Release or Bright Market)
3. v1 repo stays as-is for anyone who prefers building from source
4. Both apps can coexist on the same phone (different package names)

## Open Questions

- Domain name — `tesla-lightphone.app`? Something else?
- Option A vs B — start with A (shared key) or go straight to B (per-user)?
- Should the setup wizard support multiple vehicles? (v1 already handles this via the vehicle list)
- Should there be a "reset" in settings to re-run setup?
