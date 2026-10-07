# Customer support

`/support` submits JSON to the server-only Vercel function `/api/support`. It does not use `mailto:` or open a mail application. There are no account requirements or attachments.

## Production environment

- `SUPPORT_SMTP_USER`: `mangaroapp@gmail.com` (also the fixed default). Other sender/recipient accounts are not accepted.
- `SUPPORT_SMTP_PASSWORD`: a Gmail **App Password** for `mangaroapp@gmail.com`, stored as a Production **Secret** in the existing Vercel project. Enable two-step verification on that Google account and generate an App Password; do not use the ordinary account password. Keep it out of Git, frontend JavaScript and chat.
- `SUPPORT_FORM_SECRET`: a random server-only secret of at least 32 characters for signed form tokens and cooldown cookies. It has been provisioned in the existing Production project. Keep it stable between deployments.

Redeploy the same Vercel project after adding/changing environment variables. Mail goes over TLS to `smtp.gmail.com:465`, from and to `mangaroapp@gmail.com`; the customer's validated address is the Reply-To. Only the fixed support recipient is permitted. The email includes the ticket reference, customer email, issue category and description.

A ticket is shown only after Gmail accepts the message for the support recipient. Missing configuration or SMTP failure produces a recoverable error and preserves the form; it never fabricates a successful submission. Gmail's acceptance is not a guarantee of final inbox delivery. Gmail may also impose its own sending limits.

## Basic abuse protection

The function validates Origin and JSON size, validates fields, rejects a honeypot, verifies a one-hour signed form token and sets a signed, HttpOnly, Secure, SameSite cookie after an accepted request. The cookie enforces a 60-second cooldown in that browser even across serverless instances. It is **not** a global/IP rate limit: clearing cookies can bypass it. Vercel Firewall rules can be added separately if traffic requires stronger limits. No user data is logged, and no support database or app authentication is introduced.

## Focused checks

`node --test test/support.test.mjs` exercises validation, fixed recipient, ticket/mail content, rejected delivery, malformed/expired tokens and cooldown using a stubbed mail transport. It sends no mail. Live acceptance should submit exactly one clearly labeled check through `/support` after the Production secret is configured and verify the returned reference and inbox.

Gmail guidance: https://nodemailer.com/usage/using-gmail/
