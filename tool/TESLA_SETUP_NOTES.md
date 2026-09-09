# Tesla Fleet API Setup Notes

## One-Time Registration (Required Before API Works)

Tesla requires a partner registration before your Client ID can access the Fleet API.
Without this, you get a **412 error**: "Account must be registered in the current region."

### Step 1: Generate EC Key Pair

```bash
# Generate private key
openssl ecparam -name prime256v1 -genkey -noout -out private_key.pem

# Extract public key
openssl ec -in private_key.pem -pubout -out public_key.pem
```

### Step 2: Host Public Key

The public key must be hosted at:
`https://<your-domain>/.well-known/appspecific/com.tesla.3p.public-key.pem`

Example (replace with your domain):
`https://YOUR-GITHUB-USERNAME.github.io/.well-known/appspecific/com.tesla.3p.public-key.pem`

Push `public_key.pem` to the GitHub Pages repo at that path.

### Step 3: Get a Partner Token

```bash
curl --request POST \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=<CLIENT_ID>' \
  --data-urlencode 'client_secret=<CLIENT_SECRET>' \
  --data-urlencode 'scope=openid vehicle_device_data vehicle_cmds' \
  --data-urlencode 'audience=https://fleet-api.prd.na.vn.cloud.tesla.com' \
  'https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token'
```

Note: The auth URL for partner tokens is `fleet-auth.prd.vn.cloud.tesla.com` (not `auth.tesla.com`).

### Step 4: Register the App

```bash
curl --request POST \
  --header 'Authorization: Bearer <PARTNER_TOKEN>' \
  --header 'Content-Type: application/json' \
  --data '{"domain":"<YOUR_DOMAIN>"}' \
  'https://fleet-api.prd.na.vn.cloud.tesla.com/api/1/partner_accounts'
```

### Step 5: Verify Registration

```bash
curl 'https://fleet-api.prd.na.vn.cloud.tesla.com/api/1/partner_accounts/public_key?domain=<YOUR_DOMAIN>' \
  --header 'Authorization: Bearer <PARTNER_TOKEN>'
```

## Virtual Key Pairing (Required for Vehicle Commands)

After registration, each vehicle needs to pair with the app's virtual key.
The user does this once via the Tesla mobile app.

## App Credentials

- Client ID: (set in local.properties — TESLA_CLIENT_ID)
- Domain: (your GitHub Pages domain)
- Region: North America (fleet-api.prd.na.vn.cloud.tesla.com)

## Auth Flow in the App

1. User pastes their refresh token (obtained from Tesla OAuth)
2. App exchanges refresh_token for access_token via `auth.tesla.com/oauth2/v3/token`
3. App uses access_token to call Fleet API endpoints

## Important URLs

- Partner auth: `https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token`
- User auth: `https://auth.tesla.com/oauth2/v3/token`  
- Fleet API (NA): `https://fleet-api.prd.na.vn.cloud.tesla.com`
- Developer portal: `https://developer.tesla.com`

## TODO

- [ ] Generate and host EC public key at GitHub Pages domain
- [ ] Complete partner registration
- [ ] Remove hardcoded test tokens from code (search for TODO comments)
- [ ] Implement proper token input flow
- [ ] Set up virtual key pairing on vehicle
