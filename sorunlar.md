# Bilinen sorunlar ve test notları

Son cihaz testi: 2 Eylül 2026  
Hedef cihaz: Hometech ALFA_8ST, Android 10 (API 29), `armeabi-v7a`, 2 GB RAM

## Mevcut durum

- Uygulama cihaza kuruluyor ve foreground service çalışıyor.
- Mikrofon 16 kHz mono olarak aktif; Android tarafından susturulmuyor.
- Türkçe Google TTS motoru kullanılabiliyor.
- İlk açılışta cihaz TTS motoruyla 110 çevrimdışı ifade şablonu üretildi ve önbelleğe alındı.
- Ana ekran, tarih/saat, maskot, durum metni ve hamburger ayarlar paneli çalışıyor.
- API anahtarı ve cihaz ayarları APK güncellemelerinde `adb install -r` ile korunuyor.

## Bu oturumda düzeltilenler

### Android 10 regex çökmesi

`UtterancePattern` içindeki `\{(\w+)}` deseni masaüstü JVM'de kabul edilirken Android 10 regex motorunda uygulamayı çökertiyordu. Kapanış süslü parantezi açıkça kaçırıldı: `\{(\w+)\}`.

### Wake eşiği

Hedef tablette ölçülen gerçek "hey alf" mesafeleri `2,985`, `3,125` ve `3,405` oldu. Eski eşik `3,0` olduğu için algılama tutarsızdı. Eşik `3,5` olarak kalibre edildi.

### Alf'ın kendi sesini komut sanması

Wake cevabı `SoundPool` ile asenkron çalarken mikrofon açık kalıyordu. Alf, kendi "efendim/buradayım" cevabını kullanıcının komutu gibi işleyip komut penceresini erkenden kapatabiliyordu. Wake cevabından sonra mikrofon segmentleri 1,8 saniye bastırılıyor ve VAD birikimi temizleniyor.

### Yavaş yanıt

Kalibrasyon amacıyla açık bırakılan `LOG_RANKINGS`, her yakalamada 110 şablonun tamamını hesaplıyor ve zayıf işlemcide gerçek eşleştirme işini neredeyse iki katına çıkarıyordu. Kalibrasyon ölçümleri alındıktan sonra bu seçenek kapatıldı.

### Komut süresi

Wake cevabından sonra kullanıcıya kalan süre az olduğu için komut penceresi 6 saniyeden 10 saniyeye çıkarıldı.

## Henüz cihazda doğrulanmayanlar

Son APK aşağıdaki düzeltmelerle derlendi ve testleri geçti, ancak süre bittiği için tablete kurulup uçtan uca yeniden denenmedi:

- `WAKE_ACCEPT_DISTANCE = 3.5`
- Wake cevabı sırasında 1,8 saniyelik mikrofon bastırma
- 10 saniyelik komut penceresi
- Kalibrasyon sıralamasının kapatılması

Bir sonraki oturumun ilk işi mevcut debug APK'yı `adb install -r` ile kurmak ve şu sırayı denemek olmalı:

1. "hey alf"
2. Alf'ın wake cevabını bekle
3. "hava nasıl"
4. Ardından "saat kaç" ve "pil ne kadar" ile çevrimdışı komutları dene

## Açık teknik riskler

### İnsan sesi ile sentetik şablonlar birbirine yeterince iyi ayrılmıyor

Gerçek konuşma örneklerinde en yakın üç sonuç çoğunlukla birbirine çok yakın ve bazen alakasız zamanlayıcı cümleleri oldu. Örneğin bir yakalamada ilk sonuçlar `2,427`, `2,454`, `2,496` idi. `COMMAND_MIN_MARGIN = 0.15` bu örnekleri haklı olarak reddediyor; eşiği körlemesine gevşetmek yanlış komut çalıştırabilir.

Kalıcı çözüm, yalnızca cihaz TTS sesine dayanmamak ve kullanıcıdan gerçek ses örnekleriyle kişisel wake/komut şablonları kaydetmektir. Alternatif olarak wake sonrasındaki komut sesi doğrudan Gemini'ye gönderilebilir; yerel komut eşleştiricisi yalnızca güveni yüksek sonuçlarda kullanılabilir.

### Hava durumu uçtan uca doğrulanmadı

`hava nasıl` kataloğa ve `WeatherNowSkill` yürütücüsüne bağlı. Önceki testte cevap gelmemesinin başlıca nedeni wake cevabının mikrofona geri beslenmesi ve komut penceresini kapatmasıydı. Yankı düzeltmesi eklendi fakat yeni APK cihazda denenmedi. Düzeltmeden sonra yerel eşleşme olmazsa Gemini fallback ve hava servisi logları ayrıca kontrol edilmeli.

### İlk şablon üretimi uzun sürüyor

APK içinde hazır `templates.alf` bulunmadığında tablet 110 ifadeyi kendi TTS motoruyla tek tek üretir. Bu birkaç dakika sürüyor ve ilk deneyimi yavaşlatıyor. Yayın APK'sına workstation üzerinde üretilmiş `app/src/main/assets/templates.alf` eklenmesi önerilir.

### Gradle uyarısı

Android derlemesinde Kotlin Gradle eklentisinin birden fazla alt projede yüklendiğine dair uyarı var. Derlemeyi şu an bozmuyor, ancak Gradle 9 yükseltmesinden önce plugin classloader yapısı tekrar ele alınmalı.

## Doğrulama

Son yerel çalıştırma başarılı:

```text
./gradlew test -Palf.android=true :app:assembleDebug
BUILD SUCCESSFUL
155 actionable tasks
```

Debug APK yolu: `app/build/outputs/apk/debug/app-debug.apk`
