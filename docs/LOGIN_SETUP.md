# Login setup (Firebase) — Google, Email + Password, GitHub

Share Scan में **अकाउंट** (Menu → अकाउंट) से तीन तरह का login है: Google, email + password और GitHub।
यह Firebase Authentication से चलता है। जब तक नीचे के steps पूरे नहीं होते, app पहले जैसा चलता है और
अकाउंट sheet में बस "लॉगिन अभी सेट नहीं है" दिखता है — कुछ टूटता नहीं।

**ज़रूरी नियम:** Firebase की कोई भी value या `google-services.json` file repo में **commit नहीं** करनी है।
सारी values सिर्फ़ GitHub **repository secrets** में जाती हैं; build के समय workflow उन्हें app में डालता है।
(`google-services.json` पहले से `.gitignore` में है।)

---

## Step 1 — Firebase project बनाएँ

1. https://console.firebase.google.com खोलें → **Add project** (या **Create a project**)।
2. नाम दें, जैसे `share-scan` → Continue। Google Analytics की ज़रूरत नहीं, बंद कर सकते हैं → **Create project**।

## Step 2 — Android app जोड़ें

1. Project overview पर **Android** icon (या **Add app → Android**) दबाएँ।
2. **Android package name:** `com.rohit.share_scan` (बिल्कुल यही, वरना login नहीं चलेगा)।
3. **App nickname:** `Share Scan` (optional)।
4. **Debug signing certificate SHA-1:** नीचे वाला release SHA-1 डालें।
5. **Register app** दबाएँ। अगली screen पर `google-services.json` download का option आएगा — इसे download
   कर लें, सिर्फ़ values पढ़ने के लिए (Step 6)। **इसे repo में मत डालें।** "Add Firebase SDK" वाले steps
   skip करें (**Next → Next → Continue to console**) — app में यह काम पहले से हो चुका है।

## Step 3 — Release signing fingerprints (SHA-1 और SHA-256) जोड़ें

Google login के लिए Firebase को app की signing key का fingerprint चाहिए। हर release एक ही permanent key
(`app/sharescan-release.p12`) से sign होती है, उसके fingerprints ये हैं:

| Type | Fingerprint |
|---|---|
| SHA-1 | `8B:AF:B9:B3:AF:E6:69:72:23:6F:0B:06:E2:DA:A1:EE:34:F9:D5:0D` |
| SHA-256 | `1D:D1:BC:AC:97:FA:4A:A2:A0:4A:0A:33:8F:79:02:16:BB:9F:3B:9F:9C:03:B6:82:CD:DB:45:93:60:4F:CF:9F` |

1. Firebase console → ⚙️ **Project settings** → **General** tab → नीचे **Your apps** में Share Scan (Android)।
2. **SHA certificate fingerprints → Add fingerprint** → SHA-1 paste → **Save**।
3. फिर से **Add fingerprint** → SHA-256 paste → **Save**।

> ध्यान दें: CI का debug APK हर बार एक नई (temporary) debug key से sign होता है, इसलिए **Google login
> सिर्फ़ release APK में चलेगा** (GitHub Releases वाला `ShareScan.apk`)। Email और GitHub login पर इसका असर नहीं।

## Step 4 — तीनों sign-in providers चालू करें

Firebase console → **Build → Authentication** → **Get started** → **Sign-in method** tab:

1. **Email/Password** → Enable (पहला switch) → **Save**। ("Email link" वाला दूसरा switch ज़रूरी नहीं।)
2. **Google** → Enable → **Project support email** चुनें → **Save**।
   - Save के बाद Google provider फिर से खोलें → **Web SDK configuration** section में **Web client ID** दिखेगा
     (`1234567890-abc...apps.googleusercontent.com` जैसा)। यही `FIREBASE_WEB_CLIENT_ID` है (Step 6)।
3. **GitHub** → Enable → यहाँ **Client ID** और **Client secret** चाहिए, जो Step 5 में GitHub से मिलेंगे।
   इसी dialog में नीचे एक **authorization callback URL** दिखता है, जैसे
   `https://<project-id>.firebaseapp.com/__/auth/handler` — इसे copy कर लें।
   यह dialog खुला छोड़ दें (या Step 5 के बाद फिर खोलें)।

## Step 5 — GitHub OAuth App बनाएँ

1. GitHub पर: अपनी profile photo → **Settings** → सबसे नीचे **Developer settings** → **OAuth Apps** →
   **New OAuth App** (पहली बार हो तो **Register a new application**)।
2. भरें:
   - **Application name:** `Share Scan`
   - **Homepage URL:** `https://github.com/rohitrot738/Share_scan` (या कोई भी आपका URL)
   - **Authorization callback URL:** Step 4.3 में Firebase से copy किया URL
     (`https://<project-id>.firebaseapp.com/__/auth/handler`) — बिल्कुल वही, बिना बदलाव।
3. **Register application** दबाएँ।
4. अगली page पर **Client ID** copy करें। फिर **Generate a new client secret** → secret copy करें
   (यह सिर्फ़ एक बार दिखता है)।
5. Firebase के GitHub provider dialog में **Client ID** और **Client secret** paste करें → **Save**।

> GitHub का client secret सिर्फ़ Firebase में जाता है — इसे GitHub repository secrets में या repo में नहीं डालना।

## Step 6 — App की values कहाँ मिलेंगी

Step 4 के बाद `google-services.json` **दोबारा download** करें (Project settings → General → Your apps →
Share Scan → `google-services.json`), ताकि उसमें Google वाला OAuth client भी आ जाए। फिर:

| GitHub secret का नाम | Value | कहाँ मिलेगी |
|---|---|---|
| `FIREBASE_API_KEY` | `AIza...` से शुरू | `google-services.json` → `client[0].api_key[0].current_key` (या Project settings → General → **Web API Key**) |
| `FIREBASE_APP_ID` | `1:1234567890:android:abc123...` | `google-services.json` → `client[0].client_info.mobilesdk_app_id` (या Project settings → Your apps → Android app का **App ID**) |
| `FIREBASE_PROJECT_ID` | जैसे `share-scan-1a2b3` | `google-services.json` → `project_info.project_id` (या Project settings → **Project ID**) |
| `FIREBASE_WEB_CLIENT_ID` | `...apps.googleusercontent.com` | Authentication → Sign-in method → Google → **Web SDK configuration → Web client ID**; या `google-services.json` में `oauth_client` की वह entry जिसमें `"client_type": 3` है → उसका `client_id` |
| `FIREBASE_STORAGE_BUCKET` (optional) | जैसे `share-scan-1a2b3.appspot.com` | `google-services.json` → `project_info.storage_bucket` |
| `FIREBASE_SENDER_ID` (optional) | सिर्फ़ अंक, जैसे `1234567890` | `google-services.json` → `project_info.project_number` |

ध्यान रहे: `FIREBASE_APP_ID` **Android** app का होना चाहिए (बीच में `:android:` लिखा हो), web app का नहीं।
`FIREBASE_WEB_CLIENT_ID` में `client_type: 3` (web) वाला ID चाहिए, `client_type: 1` (Android) वाला नहीं।

## Step 6.5 — Watchlist cloud में रखने के लिए Firestore चालू करें

Login के बाद app आपकी ★ Watchlist को Cloud Firestore में रखता है, ताकि दूसरे phone पर उसी account से login करने पर
वही watchlist दिखे। इसके लिए (यह step छोड़ देंगे तो login चलेगा, बस watchlist सिर्फ़ phone पर रहेगी):

1. Firebase console → **Build → Firestore Database** → **Create database**।
2. **Location** में `asia-south1 (Mumbai)` चुनें → Next → **Start in production mode** → **Create**।
3. Database बनने के बाद **Rules** tab खोलें, पूरा text हटाकर नीचे वाले rules paste करें → **Publish**:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // हर user सिर्फ़ अपना document पढ़/लिख सकता है: users/{अपना uid}
    match /users/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    // App का इस्तेमाल: हर phone दिन में एक बार devices/{random id} लिखता है — सिर्फ़ version और समय।
    // पढ़ सिर्फ़ app बनाने वाला सकता है: GitHub account rohitrot738 (id 178737756) से login करके।
    match /devices/{device} {
      allow create, update: if device.matches('[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
        && request.resource.data.keys().hasOnly(['v', 'seen'])
        && request.resource.data.v is string
        && request.resource.data.v.size() <= 20
        && request.resource.data.seen == request.time;
      allow read: if request.auth != null
        && request.auth.token.firebase.identities['github.com'][0] == '178737756';
    }
  }
}
```

**App का इस्तेमाल (Menu → App का इस्तेमाल):** ऊपर के `devices` वाले rules से हर phone दिन में एक बार सिर्फ़ एक
random ID और अपना version लिखता है, और कितने phones पर app चल रहा है यह सिर्फ़ GitHub account rohitrot738 से
login करने पर Menu में दिखता है। पहले से database है तो Rules tab में बस यह पूरा text फिर से paste करके
**Publish** करें — watchlist वाले rules वही हैं।

पहली बार किसी phone पर login करने पर phone की watchlist और cloud की watchlist जुड़ जाती हैं (कुछ नहीं हटता)।
उसके बाद cloud वाली list ही मानी जाती है — एक phone पर ★ लगाएँ या हटाएँ, दूसरे पर भी हो जाएगा।

## Step 7 — Values को repository secrets में डालें

1. GitHub पर repo खोलें → **Settings** → **Secrets and variables** → **Actions**।
2. **New repository secret** → **Name** में ऊपर की table का नाम (बिल्कुल वैसा ही, capital letters में) →
   **Secret** में value → **Add secret**।
3. कम से कम ये चार ज़रूर बनाएँ: `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID`,
   `FIREBASE_WEB_CLIENT_ID`। बाकी दो optional हैं।
   - पहले तीन के बिना login बंद रहता है।
   - `FIREBASE_WEB_CLIENT_ID` के बिना email और GitHub चलेंगे, पर "Google से लॉगिन" button नहीं दिखेगा।
4. Values paste करते समय आगे-पीछे space या quotes (`"`) न जोड़ें।

## Step 8 — Release दोबारा बनाएँ

Secrets सिर्फ़ नए build में जाते हैं:

1. Repo → **Actions** → **Release Android APK** → **Run workflow** → branch `main` → **Run workflow**।
   - पुराने run पर "Re-run" न दबाएँ — उसका version number वही रहता है और release बनाने में टकराता है।
     हमेशा **Run workflow** से नया run चलाएँ।
2. Run हरा (✓) होने पर phone पर नया `ShareScan.apk` install करें
   (https://github.com/rohitrot738/Share_scan/releases/latest/download/ShareScan.apk) या app का update banner इस्तेमाल करें।
3. App में **Menu → अकाउंट** खोलें: अब Google, GitHub और email + password से login दिखना चाहिए।

## कुछ गड़बड़ हो तो

| दिक्कत | क्या जाँचें |
|---|---|
| अकाउंट में अब भी "लॉगिन अभी सेट नहीं है" | तीनों ज़रूरी secrets के नाम सही हैं? Secrets जोड़ने के **बाद** नया release बना और वही APK install किया? |
| "Google से लॉगिन नहीं हो पाया" | Step 3 के SHA-1/SHA-256 जुड़े हैं? `FIREBASE_WEB_CLIENT_ID` web (`client_type: 3`) वाला है? Release APK इस्तेमाल कर रहे हैं (debug नहीं)? |
| GitHub login browser में error दे | GitHub OAuth App का callback URL Firebase वाले URL से बिल्कुल मिलता है? Firebase में GitHub का Client ID/secret सही paste हुआ? |
| "यह लॉगिन तरीका अभी चालू नहीं है" | Step 4 में वह provider Enable करके Save किया? |
| Watchlist दूसरे phone पर नहीं आई | Step 6.5 में Firestore database बना और rules Publish हुए? दोनों phones पर एक ही account से login है? |
| "Email या password गलत है" (नए user पर भी) | Firebase की email enumeration protection गलत password और "user नहीं मिला" दोनों को एक ही error बनाती है — पहले "नया अकाउंट बनाएँ" से अकाउंट बनाएँ। |

Password reset और verification emails हिंदी template में जाते हैं। उनका text बदलना हो तो:
Authentication → **Templates**।
