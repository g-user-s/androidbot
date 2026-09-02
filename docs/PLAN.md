# alf — Sesli Ev Asistanı · Teknik Plan

Adanmış bir ev asistanı cihazı. Ekran kapalı ve kilitliyken "hey alf" → "efendim" → konuşma.
İnternet varken bulut LLM ile serbest konuşma, internet yokken cihaz üstü komut tanıma.

## 1. Donanım ve Bunun Dayattıkları

| | |
|---|---|
| SoC | MediaTek MT8167 — dört çekirdek, verimlilik sınıfı, big core yok |
| RAM / Depolama | 2 GB / 32 GB |
| OS | Android 10 (API 29), bootloader açık, **root var** |
| Güç | **Pille çalışacak**, sürekli prizde değil |

Bu dördü planın her kararını belirliyor:

**Sıfır güçlü uyandırma mümkün değil.** Android'in `AlwaysOnHotwordDetector` API'si ses DSP'sini
kullanır (CPU uyanmadan). Root, gereken `MANAGE_VOICE_KEYPHRASES` iznini ve varsayılan asistan
olmayı çözer — ama asıl engel izin değil: anahtar kelimenin, cihazın SoundTrigger HAL'inin
anladığı **üreticiye özel ikili model** olarak var olması gerekir. Bu blob'lar Qualcomm/Sensory
gibi lisanslı araçlarla üretilir; "hey alf" için üretilemez. MT8167 sınıfı bir tablet
platformunda SoundTrigger HAL'inin hiç bulunmaması da kuvvetle muhtemel.

**Sonuç:** mikrofonu ana işlemcide dinleyeceğiz, AP wake lock ile ayakta kalacak.
Kaba tahmin ~150-250 mW → 4000-6000 mAh pilde **3-4 gün**. Gece dinlemeyi kapatan bir zamanlama
kuralı bunun ~%30'unu geri kazandırır. "Haftalarca pilde durur" bu donanımda mümkün değil.

**Ağır motorlar elenir.** ~1.3 GHz verimlilik çekirdeğinde ve 2 GB RAM'de Vosk'un genel dil
modeliyle serbest diktesi gerçek zamanın altına inmez; whisper.cpp tiny bile çok uzak (5 sn'lik
cümle 15+ sn). Bu yüzden **cihaz üstü genel amaçlı konuşma tanıma yok.**

## 2. Temel Karar: Offline = Komutlar, Online = Serbest Konuşma

İnternet yokken asistanın **kapalı bir komut kümesini** tanıması yeterli. Bu, konuşma tanımayı
tamamen gereksiz kılıyor: ihtiyaç duyulan şey N sınıflı bir ifade eşleştirici.

| | İnternet var | İnternet yok |
|---|---|---|
| Uyandırma | Cihaz üstü eşleştirici | Cihaz üstü eşleştirici |
| Anlama | Bulut STT + Claude (tool use) | Şablon eşleştirme + kural tabanlı çözücü |
| Kapsam | Serbest dil, çok adımlı görevler | Sabit komut listesi |

Kaybedilen tek şey internetsizken açık uçlu cümle söyleyebilmek — bu donanımda gerçekçi bir
alternatifi zaten yok.

## 3. Uyandırma ve Komut Eşleştirme

**Eğitim yok.** Her komut ifadesi hazır Türkçe TTS sesleriyle sentezlenir, öznitelikleri
çıkarılır ve referans şablon olarak saklanır. Çalışma anında gelen ses aynı öznitelik uzayında
şablonlarla karşılaştırılır (DTW).

Boru hattı:

```
AudioRecord (16 kHz mono, ~250 ms bloklar)
   → VAD kapısı                       sessizlikte hiçbir şey çalışmaz
   → MFCC + CMVN                      kanal/konuşmacı farkını bastırır
   → DTW ile şablon eşleştirme        önce sadece "hey alf"
   → uyanma yanıtı klibi (rastgele)   "efendim" / "buradayım" / "dinliyorum"
   → komut penceresi açılır           şablonlar tüm komut sözlüğü
   → AssistantEngine.handle(metin)
```

**Bilinen risk:** TTS sesi ile insan sesi akustik olarak farklıdır ve şablon eşleştirme buna
duyarlıdır. Komutlar için tolere edilebilir (kısa pencere, kullanıcı cihaza yönelmiş), ama
**"hey alf" 7/24, uzaktan ve gürültüde** çalışacağı için yanlış tetikleme asıl risktir. Üç önlem:

1. Komut başına birden fazla TTS sesi (kadın/erkek, farklı motorlar) — şablon çeşitliliği.
2. CMVN ile kanal normalizasyonu.
3. Eşleştirici bir arayüz arkasında; gerekirse **kullanıcı kaydı** eklenir (her kişi ifadeyi bir
   kez söyler). Eğitim değil, sadece şablon toplama — ve doğruluğu belirgin biçimde yükseltir.

Faz 2'de ölçülecek ilk sayı: saatte yanlış tetikleme, uyandırma gecikmesi (hedef <200 ms),
sessiz bekleme pil tüketimi.

**Uyanma yanıtı neden kayıt, TTS değil:** gecikme. TTS motorunu uyandırıp sentezlemek + ses odağı
almak birkaç yüz ms ekler. Tek sabit ifade için kayıt hem anında hem tutarlı. Diğer tüm yanıtlar
`TextToSpeech` ile üretilir; Türkçe ses paketi eksikse kullanıcı `ACTION_INSTALL_TTS_DATA`'ya
yönlendirilir.

## 4. Root'un Gerçek Kazanımları

- `/system/priv-app` kurulumu + `android:persistent="true"` → servis hiç öldürülmez, Doze ile
  uğraşılmaz.
- `CAPTURE_AUDIO_HOTWORD` ile `AudioSource.HOTWORD` — cihaz destekliyorsa daha ucuz mikrofon yolu.
- Cihazın çıplaklaştırılması: GMS, sync, hücresel radyo, Wi-Fi taraması kaldırılır. Adanmış bir
  cihazda pil ve RAM kazancının büyük kısmı muhtemelen burada.
- `Settings.Secure.voice_interaction_service` doğrudan yazılarak varsayılan asistan olunabilir.

## 5. Mimari

Niyet çözümleme, yetenek kataloğu **ve tüm sinyal işleme** saf Kotlin/JVM modüllerinde: Android
SDK'sı olmadan derlenir ve test edilir, testler saniyeler içinde çalışır, emülatör gerekmez.
Android'e bağımlı olan tek şey `AudioRecord`; eşleştiricinin kendisi değil.

```
domain/assistant     [JVM]      Skill, SkillDefinition, UtterancePattern, Intent, AssistantEngine
data/nlu             [JVM]      TextNormalizer, RuleBasedIntentResolver, OfflineVocabulary
data/dsp             [JVM]      FFT, MFCC, DTW, VAD segmentleyici, PhraseMatcher, ConversationListener
data/audio           [Android]  AudioRecord beslemesi, TTS, şablon üretimi, klipler
data/llm             [Android]  Anthropic Messages API istemcisi (tool use)
data/prefs           [Android]  Ayarlar, şifreli API anahtarı
domain/skills-impl   [Android]  Skill yürütücüleri (alarm, uygulama açma, arama...)
feature/chat         [Android]  Sohbet ve dinleme durumu ekranı
feature/settings     [Android]  İzinler, sesler, anahtar
app                  [Android]  Giriş, DI, foreground service
```

### Tek kaynak ilkesi

`SkillDefinition` üç tüketiciyi birden besler:

- `RuleBasedIntentResolver` — `utterances` üzerinden eşleştirir,
- `OfflineVocabulary` — aynı ifadeleri TTS ile sentezlenecek şablon listesine çevirir,
- LLM katmanı (Faz 3) — `description` + `parameters` alanlarını tool schema'ya serialize eder.

Yeni yetenek eklemek = kataloğa bir tanım + Android tarafında bir yürütücü. Başka hiçbir yer
değişmez.

### Slot türleri

| Tür | Anlamı | Offline tanınır |
|---|---|---|
| `ENUMERATED` | Değerler katalogda sabit (saatler, süreler, kur kodları) | Evet |
| `RUNTIME` | Değerler cihazda belli olur | Evet |
| `FREE_TEXT` | Serbest metin (not içeriği) | Hayır |

`FREE_TEXT` içeren bir ifade sonlu bir listeye açılamaz, dolayısıyla offline sözlüğe girmez —
`SkillDefinition.recognisableOffline` bunu türetir, elle işaretlenmez.

### Tanınmak ile çalıştırılabilmek ayrı şeyler

`requiresNetwork`, bir yeteneğin *duyulabilmesinden* bağımsız olarak *cevaplanabilmesini*
işaretler. "hava nasıl" sabit bir ifadedir; eşleştirici onu internetsizken de tanır, ama cevabı
üretemez. İkisini ayırmak, alf'in "anlayamadım" yerine **"şu an internetim yok"** demesini
sağlar — kullanıcı açısından tamamen farklı iki şey.

## 6. Yetenekler

| Yetenek | Örnek cümle | Ağ |
|---|---|---|
| Saat | "saat kaç" | — |
| Tarih | "bugün günlerden ne" | — |
| Pil | "pil ne kadar" | — |
| Alarm | "alarmı yediye kur" | — |
| Zamanlayıcı | "beş dakika zamanlayıcı kur" | — |
| Ses | "sesi kıs" | — |
| İptal | "boş ver" | — |
| Hava (şimdi) | "hava nasıl" | Open-Meteo |
| Hava (yarın) | "yarın hava nasıl" | Open-Meteo |
| Haberler | "haberlerde ne var" | BBC Türkçe RSS |
| Döviz | "dolar kaç" | TCMB günlük kur XML |
| Not al | "not al ekmek almayı unutma" | — (serbest metin, bulut STT gerekir) |

Uygulama açma, telefonla arama ve internette arama katalogdan çıkarıldı: cihaz bir tablet,
telefon donanımı yok, ve serbest metinli arama zaten offline tanınamıyordu.

Veri kaynakları için ek bağımlılık yok — XML'e `XmlPullParser`, JSON'a `org.json`, ikisi de
Android'de yerleşik. Hava için konum, Open-Meteo'nun geocoding uç noktasıyla şehir adından bir
kez çözülüp saklanır; GPS izni gerekmez.

## 7. Faz Planı

**Faz 0 — İskelet.** ✅ Tamamlandı. Gradle + version catalog + wrapper, saf Kotlin modüller, CI.

**Faz 1 — Çekirdek asistan.** ✅ Tamamlandı. `Skill` sözleşmesi, slot modeli, 11 yetenekli Türkçe
katalog, kural tabanlı çözücü (birebir → serbest metin → bulanık eşleştirme), offline sözlük
üreticisi, `AssistantEngine`. 27 birim testi.

**Faz 2a — Eşleştirme çekirdeği.** ✅ Tamamlandı. FFT, mel filtre bankası, MFCC + CMVN,
Sakoe-Chiba bantlı DTW, adaptif gürültü tabanlı VAD segmentleyici (ön-tampon dahil),
`PhraseMatcher` (mesafe + marj kapıları), iki aşamalı `ConversationListener`, şablon dosya
biçimi. Tamamı saf Kotlin: sentetik sesle 53 test, cihaz gerekmiyor.

**Faz 2b — Android ses katmanı.** `AudioRecord` beslemesi, TTS ile şablon üretme aracı, uyanma
klipleri, foreground service + wake lock + boot receiver, `/system/priv-app` kurulum betiği.
Çıktı: ekran kapalıyken çalışan uyandırma.

**Faz 3 — Android yetenekleri + arayüz.** Skill yürütücüleri, Compose sohbet ekranı, ayarlar,
runtime slot doldurma (kurulu uygulamalar, rehber).

**Faz 4 — LLM.** Anthropic Messages API istemcisi (streaming + tool use), `SkillDefinition` →
tool schema, tool-call döngüsü, şifreli anahtar, çevrimdışında kural tabanlı çözücüye düşüş.

**Faz 5 — Sağlamlaştırma.** Cihazda pil ölçümü, yanlış tetikleme ayarı, ses odağı çakışmaları,
gece zamanlama kuralı, release imzalama.

## 8. Eşik Kalibrasyonu

`PhraseMatcher` iki kapı kullanıyor ve ikisi de cihazda ölçülerek ayarlanmalı:

- **`acceptDistance`** — normalize edilmiş DTW mesafesi üst sınırı. Sözlükteki hiçbir şeye
  benzemeyen sesleri eler.
- **`minMargin`** — kazanan ile en yakın *farklı* ifade arasındaki fark. İki ifadeye eşit
  derecede benzeyen sesi, kendinden emin bir yanlış cevaba dönüşmeden eler.

`PhraseMatcher.rank()` sıcak yolda kullanılmaz; tam olarak bu kalibrasyon için var — bir kayıt
verildiğinde ifade başına en iyi mesafeyi sıralı döndürür. Prosedür: cihazda 50-100 gerçek
"hey alf" kaydı ve birkaç saatlik oda gürültüsü topla, `rank()` çıktısının iki dağılımını
ayıran noktayı seç. Ölçülecek sayı: **saatte yanlış tetikleme** (hedef <1) ve **kaçırma oranı**
(hedef <%5).

Uyandırma sözlüğü tek ifade olduğu için orada `minMargin` işlevsizdir (karşılaştırılacak rakip
yok); asıl işini komut penceresinde görür.

## 9. Açık Sorular

- `adb shell lshal | grep -i soundtrigger` — boş dönerse DSP yolu kesin kapalı (beklenen).
- `adb shell getprop ro.product.cpu.abilist` — 32/64 bit, yerel kütüphane ABI'si için gerekli.
- Uyandırma ifadesi "hey alf" kısa; yanlış tetikleme yüksek çıkarsa daha uzun/ayırt edici bir
  ifade tek satırlık bir katalog değişikliği.
