# Managed authentication

Aether uses Neon Managed Better Auth for identity, with the provider's official `@neondatabase/auth` client pinned at `0.7.0-beta`. The client is bundled with pinned esbuild; `npm ci && npm run build:auth` regenerates `dist/auth-client.js`. The SDK is loaded lazily only when `/api/auth/config` reports enabled, keeping the disconnected static preview small. The combined `deploy/Dockerfile` serves frontend assets and the Java API from the same origin.

## User flow

Open Profile → Sign in / manage account. Create an account with name, email and a password of at least 12 characters, or sign in. The managed service owns password hashing and HTTP-only identity sessions. The frontend obtains a 15-minute API JWT, exchanges it at `/api/session/bootstrap`, then keeps the token in memory and refreshes it after ten minutes. An existing provider session restores the application profile after reload. Sign out ends the managed session and clears the in-memory application token. Previously issued JWTs can remain valid until expiry; sign-out is not immediate token revocation.

The default demo identity configuration does not require email verification and uses shared development email/OAuth infrastructure. Before production, enable email verification, configure custom SMTP and production OAuth credentials, and test recovery/verification. The fixed-destination `/api/identity` proxy keeps managed cookies on the application origin with Secure, HTTP-only and SameSite=Lax attributes. Only five identity routes are forwarded; mutation requests require the configured application Origin, unrelated cookies are stripped, and bodies/responses are bounded. Test supported browsers before a public launch. No social login UI, MFA or password-recovery UI is claimed in this release.

## Server trust boundaries

`ManagedEd25519Decoder` enables Ed25519 verification only when `OIDC_ALGORITHM=EdDSA` is explicitly configured. RS256 remains the default for existing issuers and local fixtures. The Ed25519 decoder uses Java's signature verification with trusted, bounded-size JWKS responses, a five-minute key cache, throttled unknown-key refresh, mandatory expiry/subject, and Spring issuer/audience/time validation. Untrusted token URLs, unsupported algorithms and critical JOSE extensions are rejected. The provider's current issuer and audience are its origin, while the JWKS URL includes the branch/database auth path. No JWT role claim grants application permissions.

`AccountService.bootstrap` serializes onboarding by trusted JWT subject using a PostgreSQL advisory transaction lock. New identities receive a viewer role and one Main profile. Repeated calls reuse the same user/profile, and existing operator-assigned roles are preserved. Profile ownership and admin checks remain in the existing service layer.

Admin promotion is an operator action after verifying the owner's identity subject. Use a trusted migration/administration connection, verify exactly one matching user, and update its role; never accept a role supplied by the browser or by a provider's generic `authenticated` claim. Do not promote the synthetic integration fixture.

## Deployed identity resources

Free-plan Neon organization, project `aether-auth` (`mute-sunset-57539443`), AWS Oregon, production branch `br-ancient-art-aryqgrnx`. Application business data stays in Render PostgreSQL; the separate Neon database contains managed identity state. No existing database was migrated or replaced. No LLM gateway or API key was enabled.

The public auth URL and JWKS location are deployment configuration, not secrets. Backend database credentials and the private recommendation-service credential remain out of source control. The disposable local signer in `infra/local/` is never included in the cloud Blueprint.

## Verification

52 Java tests passed against disposable PostgreSQL, Redis and Python, including real Ed25519 signature/issuer/audience/expiry/algorithm/tampering checks, account bootstrap idempotence, ignored provider admin claims and endpoint authentication. Six frontend tests passed, including cookie-based API session restoration without relying on the SDK session cache. A synthetic managed identity successfully signed up and obtained a real JWT; its credentials are kept only in a protected local verification file. The local proxy successfully signed in the existing synthetic identity, restored its managed session and issued a JWT. Public browser reload restored the viewer account and sign-out succeeded; identity provisioning alone is not proof of working sign-in.

References: [Neon Auth](https://neon.com/docs/auth/overview), [managed JWT contract](https://neon.com/docs/auth/guides/plugins/jwt).

Browser proof: `screenshots/18-cloud-session-restored.png` shows the restored Viewer account with empty credential fields after page reload on the public HTTPS deployment.
