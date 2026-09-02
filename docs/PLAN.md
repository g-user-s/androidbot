# alf — Android 10 Sesli Asistan · Teknik Plan

Uygulama adı: **alf**
Hedef: Android 10 (API 29), stock/AOSP+GMS cihaz.
Ana senaryo: Ekran kapalı ve kilitliyken "hey alf" → "efendim" → serbest konuşma.
İnternet varsa bulut LLM, yoksa tamamen cihaz üstü çalışmaya düşer.

## 1. Kapsam

**v1 (offline iskelet):** Wake word, offline STT, TTS, kural tabanlı niyet çözümleme, temel cihaz yetenekleri.
**v2 (LLM):** Aynı yetenekler Anthropic Messages API'ye tool olarak sunulur; internet yoksa otomatik olarak v1 davranışına düşer.

Kapsam dışı: AccessibilityService ile UI otomasyonu, sistem varsayılan asistan rolü.

## 2. Neden Kendi Wake Word Motorumuz

Android'in `AlwaysOnHotwordDetector` API'si ses DSP'sini kullanır (CPU uyanmadan, neredeyse sıfır pil) ama üçüncü parti uygulamalara kapalıdır:

- Uygulamanın cihazın aktif `VoiceInteractionService`'i olması gerekir,
- `MANAGE_VOICE_KEYPHRASES` izni `signature|privileged` seviyesindedir (platform anahtarıyla imza veya `/system/priv-app` kurulumu),
- Anahtar kelime modelinin DSP'ye üretici tarafından gömülmüş olması gerekir; "hey alf" oraya sonradan eklenemez.

Sonuç: uyandırma sözcüğü ana CPU üzerinde, kendi mikrofon akışımızda tespit edilecek. Pil maliyeti bu kararın doğrudan sonucudur ve Bölüm 4'teki VAD katmanı bunu telafi etmek içindir.

## 3. Ses Yığını — Kararlar

| Katman | Seçim | Not |
|---|---|---|
| Wake word | **Vosk**, kısıtlı gramer `["hey alf", "[unk]"]` | Tam offline, Apache-2.0, lisans/AccessKey derdi yok |
| STT | **Vosk** + `vosk-model-small-tr` (~40 MB) | Android 10'un `SpeechRecognizer`'ı internetsiz çalışmaz (cihaz üstü tanıma Android 12+) |
| "efendim" yanıtı | **Gömülü ses dosyası** (mp3/ogg) | Gecikme <200 ms; TTS init + sentez + ses odağı gecikmesini atlar |
| Diğer tüm cevaplar | Platform `TextToSpeech` | Açılışta `isLanguageAvailable(Locale("tr"))` kontrolü |

**TTS güvenilirlik notu:** Türkçe TTS genelde mevcuttur ama garanti değildir — GMS'siz cihazda Google TTS yoktur (AOSP Pico Türkçe desteklemez), kullanıcı dil verisini silmiş olabilir, veya Türkçe ses "ağ gerektiren" varyant olarak işaretli olabilir. Eksikse kullanıcı `ACTION_INSTALL_TTS_DATA`'ya yönlendirilir; uyandırma yanıtı gömülü klip olduğu için asistan bu durumda da yanıt verir.

Model dosyası: ilk açılışta indirilip app-specific dizinde saklanır (APK boyutunu şişirmemek için), indirilene kadar sadece metin girişi aktif.

## 4. Sürekli Dinleme Boru Hattı

```
AudioRecord (16 kHz mono, sürekli açık)
   → VAD (enerji/WebRTC tabanlı kapı)        ← sessizlikte Vosk çalışmaz
   → Vosk KWS (kısıtlı gramer)               ← "hey alf" tespiti
   → "efendim" klibi çalınır (anında)
   → Vosk STT (tam model, tek cümle, ~6 sn sessizlik timeout)
   → AssistantEngine.handle(text)
       → çevrimiçi ise LlmResolver, değilse RuleBasedResolver
       → SkillRegistry.find(intent).execute(params)
   → TextToSpeech ile yanıt
   → KWS moduna dön
```

VAD katmanı zorunlu: Vosk genel amaçlı bir tanıyıcıdır, adanmış bir KWS değil — sürekli decode bir çekirdeğin ~%15-25'ini yer. VAD ile sessizlikteki maliyet ihmal edilebilir seviyeye iner.

Ölçülecek: sessiz bekleme modunda saatlik pil tüketimi (hedef <%3/saat), wake word yanlış tetikleme oranı, "hey alf" → "efendim" gecikmesi (hedef <200 ms).

## 5. Ekran Kapalı / Kilitli Çalışma

- `dataSync` tipli foreground service (Android 10'da `microphone` service tipi yok — o API 30+).
- `PARTIAL_WAKE_LOCK` — Doze'da CPU'nun ses işlemeye devam etmesi için.
- Foreground service, arka plan mikrofon kısıtından muaftır; ekran kapalıyken kayıt yapabilir.
- `RECEIVE_BOOT_COMPLETED` ile yeniden başlatmada otomatik ayağa kalkar.
- `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — stock Adaptive Battery uzun vadede kısıtlayabilir.
- Ses odağı: telefon görüşmesi veya başka bir uygulama mikrofonu aldığında servis durur, bırakıldığında geri döner.
- Görsel arayüz gerekmez (yanıt sesli); istenirse `setShowWhenLocked` + `setTurnScreenOn` ile kilit üstü ekran eklenebilir.

## 6. Teknoloji Seçimleri

| Alan | Seçim |
|---|---|
| Dil / Build | Kotlin 2.x, Gradle KTS + version catalog, AGP 8.x |
| SDK | minSdk 29, targetSdk 34, compileSdk 35 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Async | Coroutines + Flow |
| Kalıcılık | Room (konuşma geçmişi), DataStore (ayarlar) |
| Ağ | OkHttp + kotlinx.serialization |
| Sır saklama | androidx.security-crypto (API anahtarı) |
| Test | JUnit4, Robolectric, Turbine, MockK |
| CI | GitHub Actions: assembleDebug + unit test + lint |

## 7. Modül Yapısı

```
:app                    Giriş, DI grafiği, navigasyon
:core:ui                Tema, ortak Compose bileşenleri
:core:common            Result tipleri, dispatcher'lar, log
:domain:assistant       AssistantEngine, Intent, Skill arayüzü, SkillRegistry
:data:audio             AudioRecord, VAD, Vosk KWS + STT, TTS, ses klibi
:data:nlu               RuleBasedResolver (v1), LlmResolver (v2)
:data:prefs             Ayarlar, şifreli anahtar deposu
:feature:chat           Sohbet ekranı, mikrofon/dinleme durumu
:feature:settings       İzinler, model indirme, ses/dil, API anahtarı
```

### Skill sözleşmesi

```kotlin
interface Skill {
    val id: String
    val description: String            // v2'de LLM tool açıklaması
    val parameters: List<ParamSpec>    // v2'de JSON schema
    suspend fun execute(params: Map<String, String>): SkillResult
}
```

Kural tabanlı çözücü `Skill`'i doğrudan çağırır; LLM'e geçildiğinde aynı metadata `tools` dizisine serialize edilir. **Skill kodları v1'den v2'ye değişmez.**

### v1 skill listesi
`open_app`, `set_alarm`, `set_timer`, `call` (ACTION_DIAL), `send_sms`, `web_search`, `device_info` (pil/saat/tarih), `note` (Room)

## 8. Faz Planı

**Faz 0 — İskelet (0.5 gün).** Gradle + modüller + Hilt + Compose tema + boş sohbet ekranı + CI.

**Faz 1 — Metin asistanı (1–2 gün).** AssistantEngine, Skill/SkillRegistry, kural tabanlı resolver (TR/EN), 8 skill, Room geçmişi. Metinle uçtan uca çalışır, hiçbir izin gerektirmez.

**Faz 2 — Ses boru hattı (3–4 gün) — projenin en riskli kısmı.** AudioRecord + VAD, Vosk entegrasyonu ve model indirme, wake word tespiti, "efendim" klibi, STT, TTS, foreground service + wake lock + boot receiver, RECORD_AUDIO izin akışı. Çıktı: ekran kapalıyken "hey alf" ile uyanan asistan.

**Faz 3 — LLM (2–3 gün).** Anthropic Messages API istemcisi (streaming + tool use), Skill → tool schema dönüşümü, tool-call döngüsü, şifreli anahtar, çevrimdışı/hata durumunda kural tabanlı çözücüye otomatik düşüş.

**Faz 4 — Sağlamlaştırma (2 gün).** Gerçek cihazda pil ölçümü, yanlış tetikleme ayarı, Doze testi, ses odağı çakışmaları, ProGuard, release imzalama.

Toplam: ~9–13 gün.

## 9. Açık Sorular

- Cihaz rootlu mu / kendi ROM'unu imzalayabiliyor musun? Öyleyse `/system/priv-app` kurulumu gerçek DSP wake word yolunu açar — pil tüketimi neredeyse sıfıra iner.
- Vosk Türkçe modelinin "hey alf" üzerindeki yanlış tetikleme oranı sahada ölçülmeli; kabul edilemezse alternatif adanmış KWS (TFLite ile eğitilmiş özel model) değerlendirilir.
