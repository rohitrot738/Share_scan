# 💰 बड़े पैसे की चाल — design

तारीख: 5 Nov 2026 (IST) · स्थिति: user की समीक्षा के लिए

## समझ

**User ने कहा:** बड़े पैसे (institutions, operators) की चाल data से पकड़नी है, चार्ट देखकर नहीं:
delivery spike (चुपचाप खरीद), operator churn (नकली volume), bulk/block deals का net असर, insider/SAST
की खबर। Live order-book बाद में।

**User ने चुना:**
- पहले सिर्फ़ शाम का (EOD) data; broker WebSocket / live order-book इस काम में नहीं।
- Notification: watchlist के शेयर पर signal आए तो उसका अलग alert, और शाम को पूरे market की एक digest।
- रास्ता A: अलग शाम का job, app की अपनी file।

**मान्यताएँ (user ने सुधारी नहीं):** मकसद swing/positional के लिए चुपचाप खरीदे जा रहे शेयर पकड़ना और
operator के जाल वाले शेयरों से बचना; interface यही app (Streamlit/Telegram नहीं)।

**सफल तब:** हर शाम NSE data आने के कुछ मिनट में app में हर NSE शेयर के चार signal और score हों, हर signal के
साथ उसका पिछले एक साल का ईमानदार track record, watchlist पर alert और शाम की digest; कोई आँकड़ा गढ़ा न जाए।

**दायरे से बाहर:** live order-book और tick data; BSE-only शेयर (BSE की delivery file बाद में); Telegram;
thresholds बदलने की setting।

**नाम:** trade window में "Smart money" पहले से FVG/order blocks का नाम है, इसलिए यह section
"💰 बड़े पैसे की चाल" (Hinglish: "Big Money") कहलाएगा।

## हिस्सा 1: data pipeline

नया workflow `.github/workflows/big-money.yml`, Python `bigmoney/` में।

- **कब:** हफ़्ते के दिनों में 19:15 IST, और 21:40 IST पर दूसरी कोशिश (पहली में file न मिली हो तो)।
  `workflow_dispatch` भी।
- **Delivery:** NSE archives की `sec_bhavdata_full_DDMMYYYY.csv` (हर शेयर का close, traded quantity,
  deliverable quantity, delivery %)। NSE main और SME दोनों।
- **पहली बार:** पिछले लगभग 1 साल (≈250 सत्र) की files; उसके बाद हर शाम सिर्फ़ उस दिन की। जो सत्र store में नहीं
  है (छुट्टी के बाद, job छूटा हो) वह अगली बार अपने-आप भर जाता है।
- **Deals:** NSE की रोज़ की `bulk.csv` और `block.csv` (R/S job जैसे ही), client के नाम के साथ; पिछला साल
  R/S store (`cr360-store.json.gz`) के deals से।
- **Promoter/insider:** R/S store के insider trades (category और mode के साथ, हफ़्ते में अपडेट); probe में
  NSE की पूरे market की रोज़ की PIT सूची मिली तो वह रोज़ जोड़ी जाएगी (नीचे "Probe के नतीजे")।
- **Store:** `big-money-store.json.gz` (release `cr360-data` में): हर शेयर के हर सत्र का close, volume,
  deliverable quantity, series; 1 साल से पुराना हटता है।
- **App की file:** `big-money.json.gz` (उसी release में): data की तारीख, हर signal का track record, और हर शेयर
  के आज के signal, score के हिस्से, deals/promoter का net, पिछले 20 सत्र का delivery % और volume।
- **गड़बड़ी:** छुट्टी पर file नहीं (वह दिन छूटता है); file न मिले तो पिछली file ही release में रहती है; टूटी
  file (कम पंक्तियाँ, गलत columns) पर publish नहीं; कोई आँकड़ा गढ़ा नहीं जाता, न मिले तो खाली।
- **NSE की bot सुरक्षा:** archive files और filings वैसे ही पढ़े जाते हैं जैसे R/S job पढ़ता है (सामान्य
  browser headers, requests के बीच रुककर)। Playwright/curl_cffi से bot जाँच बायपास नहीं।

## हिस्सा 2: signals, score, track record

हर शाम, हर शेयर पर (`bigmoney/rules.py`, शुद्ध functions):

1. **🟢 चुपचाप खरीद:** आज delivery % > पिछले 20 सत्रों के औसत delivery % × 1.8, आज भाव का बदलाव ±2% के
   अंदर, आज volume > 1,00,000 शेयर। पिछले 20 में से कम से कम 15 सत्रों का data ज़रूरी। BE/BZ जैसे
   trade-to-trade शेयर (delivery हमेशा 100%) नहीं गिने जाते।
2. **🔴 Operator churn / जाल:** आज volume > पिछले 20 सत्रों का औसत volume × 4, और delivery % < 20।
3. **🤝 Deals का net:** पिछले 30 सत्रों की bulk+block deals: सब clients की कुल खरीद − कुल बिक्री (शेयर और ₹)।
   "लगातार net खरीद" जब deal वाले आख़िरी 2+ दिनों में हर दिन net खरीद रही; "लगातार net बिक्री" उल्टा।
   **⚠️ गोल-गोल सौदा:** जिस client ने 5 सत्रों के अंदर उसी शेयर में खरीदा भी और बेचा भी, उसका नाम।
4. **👔 Promoter:** पिछले 90 दिन, promoter/promoter group की market से खरीद − market से बिक्री (₹)। ESOP,
   off-market, gift, pledge नहीं गिने जाते।

**Score 0–100** (हर हिस्सा app में दबाकर खुलता है):

| हिस्सा | अंक |
|---|---|
| Delivery spike: आज का delivery % औसत का 1.0× से 3.0× (बीच में सीधी रेखा) | 0–30 |
| जमा होना: पिछले 5 सत्रों का औसत delivery % ÷ 20 सत्र का औसत, 1.0× से 1.5× | 0–20 |
| Deals net खरीद: 30 सत्र की net खरीद ÷ 20 सत्र का औसत रोज़ का volume, 0 से 1 दिन जितना; लगातार हो तो पूरे | 0–25 |
| Promoter net खरीद: ₹0 से ₹5 करोड़ | 0–25 |
| Churn आज हो | −30 |
| Deals या promoter की net बिक्री | उसी पैमाने पर घटेगा |

कुल 0 से नीचे 0, 100 से ऊपर 100।

**Track record** (`bigmoney/record.py`): store के पिछले साल के हर सत्र पर वही नियम चलाकर, हर signal के हर
मौके पर 10 सत्र बाद का भाव का बदलाव (close से close), उन्हीं दिनों के सभी शेयरों के औसत से तुलना, और कितने % बार
ऊपर। Score के लिए भी: 60+ score वाले दिनों का। App में: "पिछले साल 1,240 बार; 10 दिन बाद औसत +1.9% (सब शेयर
+0.7%), 56% बार ऊपर"। औसत से बेहतर न निकले तो साफ़ लिखा जाएगा।

## हिस्सा 3: app

- **Menu → 💰 बड़े पैसे की चाल** (Intraday के बाद): ऊपर data की तारीख ("4 Nov शाम का NSE data"), चार tab:
  चुपचाप खरीद · Churn/जाल · Deals · Promoter। हर tab में शेयर ताक़त के क्रम में, ज़रूरी आँकड़े (आज delivery %
  बनाम औसत, भाव का बदलाव, volume कितने गुना / net ₹), और उस tab का track record। शेयर दबाएँ तो stock detail।
- **Stock detail:** नया card "💰 बड़े पैसे की चाल": score और उसके हिस्से (दबाकर हिसाब), पिछले 20 सत्र का
  delivery % (bars) और volume, आज के signals, deals का net (30 सत्र, गोल-गोल सौदे वाले client), promoter का
  net (90 दिन), track record की लाइन। Data न हो तो "data नहीं मिला"।
- **Scan list:** card/row पर छोटा badge (🟢 जमा / 🔴 जाल); Filters में "बड़े पैसे" का विकल्प: सिर्फ़ चुपचाप
  खरीद वाले, churn वाले छिपाएँ।
- **Alerts:** शाम (19:30–23:00 IST, हफ़्ते के दिन) background check नई file देखता है; watchlist के शेयर पर नया
  signal आए तो उसका अलग notification; पूरे market की एक digest ("आज 23 शेयरों में चुपचाप खरीद: A, B, C")।
  दिन में एक ही बार। Settings में on/off (पहले से on)। Notification दबाएँ तो section/शेयर खुले।
- **File:** `BigMoneyRepository` दिन में एक बार (शाम के बाद नई) `big-money.json.gz` लाता है, एक ही copy रखता
  है; टूटी file पर पुरानी रहती है।
- **साथ में:** 12 भाषाओं में strings, help entry, changelog line, शुरुआती गाइड में नया हिस्सा (screenshot,
  शब्दकोश: delivery %, deliverable quantity, churn, bulk deal, block deal, T2T, accumulation; आम सवाल)।

## इकाइयाँ

| इकाई | काम | निर्भरता |
|---|---|---|
| `bigmoney/nse_files.py` | delivery और deal files लाना और पढ़ना | `cr360.build_app_cache.http_get` |
| `bigmoney/rules.py` | चार signal और score, शुद्ध functions | कुछ नहीं |
| `bigmoney/record.py` | track record | `rules.py` |
| `bigmoney/build.py` | store ऊपर करना, app की file लिखना | ऊपर के तीनों, R/S store |
| `BigMoneyBook.kt` | app की file पढ़ना (शुद्ध Kotlin) | कुछ नहीं |
| `BigMoneyRepository.kt` | download और copy | `BigMoneyBook` |
| `BigMoneySheet.kt` | Menu section | `BigMoneyBook` |
| `BigMoneyCard` (stock detail में) | card | `BigMoneyBook` |
| `BigMoneyAlerts.kt` | watchlist alert और digest | `BigMoneyRepository`, `Notifier` |

## जाँच (tests)

- Python: CSV पढ़ना (असली file के नमूने से), हर नियम की सीमाएँ (1.8× के ठीक ऊपर/नीचे, ±2%, 1 लाख, 15 सत्र),
  T2T छोड़ना, deals net और लगातार, गोल-गोल सौदा, promoter में ESOP/off-market छोड़ना, score के छोर, track record
  का हाथ से गिना उदाहरण, store में पुराना हटना और छूटे सत्र भरना।
- Kotlin: `BigMoneyBook` का पढ़ना, खाली/टूटी file; alerts में "नया signal" की पहचान और दिन में एक बार।
- `GuideAssetTest`: नया Menu entry guide में।
- CI APK build, workflow की एक असली run, emulator screenshots।

## Probe के नतीजे

(probe चलने के बाद भरा जाएगा)
