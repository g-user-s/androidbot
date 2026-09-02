# Android 10 Asistan Uygulaması — Teknik Plan

Hedef: Android 10 (API 29) üzerinde çalışan, sesli/yazılı komut alan bir asistan.
Karar katmanı önce kural tabanlı (offline), ikinci aşamada bulut LLM (Claude) ile değiştirilebilir.

## 1. Kapsam

**v1 (kural tabanlı):** Metin + ses ile komut al, niyeti kural tabanlı çöz, cihaz aksiyonunu çalıştır, sonucu sesli/yazılı döndür.

**v2 (LLM):** Aynı yetenekler Anthropic Messages API'ye "tool" olarak sunulur; serbest dil anlama ve çok adımlı görevler.

Kapsam dışı (şimdilik): wake-word ("hey ..." ile uyandırma), sistem varsayılan asistan rolü (VoiceInteractionService), AccessibilityService ile UI otomasyonu.

## 2. Teknoloji Seçimleri

| Alan | Seçim | Gerekçe |
|---|---|---|
| Dil | Kotlin 2.x | Standart |
| Build | Gradle KTS + AGP 8.x | Version catalog ile bağımlılık yönetimi |
| SDK | minSdk 29, targetSdk 34, compileSdk 35 | Android 10 tabanı, güncel API'lerle derleme |
| UI | Jetpack Compose + Material 3 | minSdk 21 desteği, Android 10'da sorunsuz |
| DI | Hilt | Modüller arası bağımlılık |
| Async | Coroutines + Flow | |
| Kalıcılık | Room (konuşma geçmişi), DataStore (ayarlar) | |
| Ağ | OkHttp + kotlinx.serialization | LLM istemcisi (v2) |
| Sır saklama | androidx.security-crypto (EncryptedSharedPreferences) | API anahtarı cihazda şifreli |
| STT | `SpeechRecognizer` (platform) | Android 10'da Google app'e bağımlı, online |
| TTS | `TextToSpeech` (platform) | Offline dil paketi varsa çalışır |
| Test | JUnit4, Robolectric, Turkine/Turbine, MockK | |
| CI | GitHub Actions (assembleDebug + unit test + lint) | |

## 3. Mimari

Tek yönlü veri akışı (UI → ViewModel → UseCase → Repository), üç katman.

```
:app                    Uygulama girişi, DI grafiği, navigasyon
:core:ui                Tema, ortak Compose bileşenleri
:core:common            Result tipleri, dispatcher'lar, log
:domain:assistant       AssistantEngine, Intent modeli, Skill arayüzü
:data:nlu               RuleBasedResolver (v1), LlmResolver (v2)
:data:speech            SttController, TtsController
:data:prefs             Ayarlar, şifreli anahtar deposu
:feature:chat           Sohbet ekranı + mikrofon butonu
:feature:settings       İzinler, ses/dil, API anahtarı
```

### Akış

```
Kullanıcı (metin | mikrofon)
   → SttController (ses ise)
   → AssistantEngine.handle(text)
       → IntentResolver (kural | LLM)
       → SkillRegistry.find(intent).execute(params)
   → AssistantReply(text, speak: Boolean)
   → TtsController + sohbet listesi
```

### Skill (yetenek) sözleşmesi

```kotlin
interface Skill {
    val id: String
    val description: String            // v2'de LLM tool açıklaması olur
    val parameters: List<ParamSpec>    // v2'de JSON schema'ya çevrilir
    suspend fun execute(params: Map<String, String>): SkillResult
}
```

Bu sözleşme kritik: v1'de kural tabanlı çözücü doğrudan `Skill`'i çağırır, v2'de aynı metadata `tools` dizisine serialize edilir. **LLM'e geçerken skill kodları değişmez.**

### v1 skill listesi
- `open_app` — uygulama açma (PackageManager)
- `set_alarm` — `AlarmClock.ACTION_SET_ALARM`
- `set_timer` — `AlarmClock.ACTION_SET_TIMER`
- `call` — `Intent.ACTION_DIAL` (CALL_PHONE izni istemeden, dial ekranı)
- `send_sms` — SMS uygulamasını önceden doldurulmuş açar
- `web_search` — `Intent.ACTION_WEB_SEARCH`
- `device_info` — pil, saat, tarih
- `note` — Room'a yerel not

## 4. Android 10'a Özel Riskler ve Karşılıkları

1. **Arka planda Activity başlatma yasağı (API 29'da geldi).** Uygulama arka plandayken `startActivity` sessizce düşer. Karşılık: aksiyonları ön planda yürüt; arka plan tetikleyicileri için `fullScreenIntent` taşıyan yüksek öncelikli bildirim kullan.
2. **Foreground service tipleri.** `microphone` ve `camera` service tipleri API 30+. Android 10'da dinleme servisi `dataSync` tipiyle çalışır; manifest'te `tools:targetApi` ile ayrıştır.
3. **Scoped storage.** Doğrudan dosya yolu yok; MediaStore veya app-specific dizin kullan.
4. **SpeechRecognizer offline değil.** API 29'da cihaz üstü tanıma yok (`EXTRA_PREFER_OFFLINE` garantisiz), Google uygulaması gerekli. Karşılık: STT yoksa metin girişine düş, hata mesajını net ver.
5. **TTS dil paketi eksikliği.** `TextToSpeech.LANG_MISSING_DATA` → kullanıcıyı `ACTION_INSTALL_TTS_DATA`'ya yönlendir.
6. **Üretici pil optimizasyonları** (Xiaomi/Huawei/Samsung) servisi öldürür. Karşılık: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` yönlendirmesi + servis yeniden başlatma.
7. **Cleartext trafiği kapalı.** Tüm ağ çağrıları HTTPS; `network_security_config.xml` ile açıkça belirt.
8. **Runtime izinler.** RECORD_AUDIO tek zorunlu izin; kalanları skill kullanılınca iste (just-in-time), reddedilirse graceful degrade.

## 5. Faz Planı

**Faz 0 — İskelet (0.5 gün)**
Gradle KTS + version catalog, modül yapısı, Hilt, Compose tema, boş sohbet ekranı, GitHub Actions CI. Çıktı: Android 10 emülatörde açılan uygulama.

**Faz 1 — Metin asistanı (1–2 gün)**
`AssistantEngine`, `Skill` arayüzü, `SkillRegistry`, kural tabanlı `IntentResolver` (regex + anahtar kelime + Türkçe/İngilizce sözlük), yukarıdaki 8 skill, Room ile konuşma geçmişi. Çıktı: metinle çalışan tam akış + resolver birim testleri.

**Faz 2 — Ses (1–2 gün)**
`SttController` (SpeechRecognizer, kısmi sonuçlar, hata haritalama), `TtsController` (kuyruk, dil seçimi, durdurma), mikrofon butonu ve dinleme durumu UI'ı, RECORD_AUDIO izin akışı, `dataSync` tipli foreground service. Çıktı: bas-konuş asistan.

**Faz 3 — LLM entegrasyonu (2–3 gün)**
Anthropic Messages API istemcisi (streaming + tool use), `Skill` metadata → tool schema dönüşümü, tool-call döngüsü (model → tool → sonuç → model), sistem promptu, EncryptedSharedPreferences'ta API anahtarı, ayarlarda "kural / LLM" anahtarı. Hata durumunda kural tabanlı çözücüye otomatik düşüş. Çıktı: serbest konuşan asistan.

**Faz 4 — Sağlamlaştırma (1–2 gün)**
Gerçek Android 10 cihazda test, üretici pil kısıtları, offline davranış, hata mesajları, ProGuard kuralları, release imzalama, temel UI testleri.

Toplam: ~6–10 gün geliştirme.

## 6. İlk Adım Kararı

Faz 0 + Faz 1 tek PR olarak gider; böylece LLM'siz, izinsiz, tamamen offline çalışan bir uygulama ilk günden emülatörde denenebilir.
