# Zero to App: universal translator web app

## Getting started

### Webapp

0. [Add Firebase to your Web Project](https://firebase.google.com/docs/web/setup) and copy the configuration parameters to `public/index.html`
0. Log in to the [Firebase Console](https://console.firebase.google.com/).
0. Go to Auth tab and enable Google authentication.
0. Deploy the html translation viewer to [Firebase Hosting](https://firebase.google.com/docs/hosting/quickstart)

### Cloud Functions

0. Upgrade your Firebase project to a paid plan.
0. Log in to the [Cloud Console](https://console.cloud.google.com).
0. Go to **API Manager** -> **Library** and enable both the Google Cloud Translation API and the Google Cloud Speech API.
0. Go to **IAM & Admin** -> **Service Accounts**.
0. Create a new service account with **Project -> Viewer** role.  Furnish a new private key, with key type json.  A credential file will download.  Rename it to `service-account-credentails.json` and copy it to `/functions`.
0. Deploy the Cloud Functions [with the Firebase CLI](https://firebase.google.com/docs/functions/get-started)

## Files

- `functions/index.start.js` - The Cloud Functions before any live coding has been done
- `functions/index.js` - The Cloud Functions after live coding has been completed
- `public/index.html` - The web application which reads and speaks the latest translation

## Learn more

- [Firebase Database](https://firebase.google.com/docs/database)
- [Firebase Storage](https://firebase.google.com/docs/storage/)
- [Cloud Functions for Firebase](https://firebase.google.com/docs/functions/)