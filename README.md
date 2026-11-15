# JARVIS Android Asistan

J.A.R.V.I.S.’ten esinlenen, Kotlin ile geliştirilmiş Android uygulaması — **v0.2.0**.

## Ne yapar?

Uygulama, açıkça tanımlanmış yerel ifadeleri yönlendirir ve yapılandırılmış AI hizmetine istek gönderebilir. Yerel komutlar keyfi alt dize eşleşmeleriyle tetiklenmez. Hatırlatıcı, uyandırma sözcüğü, akıllı ev kontrolü, biyometri veya otonom eylem özellikleri yoktur.

Sesli giriş bas-konuş biçimindedir: konuşma metne çevrilir, kullanıcı metni düzenler ve göndermeyi ayrıca onaylar. Konuşma tanıyıcı uygulama duraklatıldığında iptal edilip yok edilir.

Sohbet geçmişi sınırlı tutulur. Ekran döndürme gibi yapılandırma değişikliklerinde anahtar, geçmiş, taslak ve konuşma dökümü bellekteki oturumda korunur. Uygulama süreci veya oturum sona ererse bunlar kaybolur. Uç nokta, model ve ses tercihleri özel olarak kaydedilir; API anahtarı kaydedilmez.

## Kullanım

- AI uç noktasını, modelini ve ses tercihlerini yapılandırın.
- Sesli giriş için bas-konuş özelliğini kullanın; tanınan metni gözden geçirip düzenleyin ve göndermeyi ayrıca seçin.
- Durdurma veya sohbeti temizleme, devam eden isteği en iyi çabayla iptal eder. İstekle önceden gönderilmiş veriler geri alınamaz.
- Sohbeti temizleme yerel sohbet içeriğini temizler; bulut hizmetindeki verileri silmez.
- HTTP hata mesajları ayıklanmış biçimde gösterilir.
- Cihazda metin okuma (TTS) kullanılamazsa sesli yanıt için buna güvenmeyin.

`FLAG_SECURE`, normal ekran görüntülerini ve son uygulamalar önizlemelerini engeller; mutlak bir gizlilik koruması değildir.

## Gizlilik ve dağıtım

Bu proje kişisel kullanım amaçlı bir prototiptir, üretim uygulaması değildir. Dağıtımdan önce istemci uygulamasında anahtar kullanmak yerine kimlik doğrulamalı bir arka uç değerlendirilmelidir. Bulut tabanlı ses ve AI hizmetleri gizlilik etkileri ve olası kullanım ücretleri doğurabilir.

## Kurulum ve derleme

Gereksinimler: **JDK 17**, **Android SDK 35** ve **Gradle 8.7**. Depoda Gradle Wrapper bulunmuyor. Kurulu Gradle 8.7 ile proje kökünde önce wrapper oluşturun:

```sh
gradle wrapper --gradle-version 8.7
```

Ardından testleri, lint’i ve debug APK derlemesini çalıştırın:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows’ta `gradlew.bat` kullanın:

```bat
gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Android Studio’da proje kökünü açın; SDK konumunu IDE’den veya `local.properties` üzerinden belirtin, Gradle 8.7 ve JDK 17’yi seçin.

Başarılı yerel derlemeden sonra beklenen APK yolu:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Bu çalışma kapsamında APK üretilmedi.

## Doğrulama

Statik inceleme yapıldı. `CommandRouterTest` için JUnit 4 test kaynağı eklendi; **test henüz çalıştırılmadı**. Derleme, lint, telefon/emülatör veya API testi yapılmadı.

Yerel doğrulama kontrol listesi:

- Tanımlı yerel komutları ve komut ifadelerinin yalnızca alt dizesiyle eşleşen girdileri kontrol edin.
- Mikrofon iznini reddedip izin reddi davranışını doğrulayın.
- Tanınan konuşmanın açık gönderme onayı olmadan gönderilmediğini kontrol edin.
- Ekranı döndürün: oturum belleğinin korunduğunu ve sürmekte olan isteğin iptal edildiğini doğrulayın.
- Süreci sonlandırın: anahtar ve geçmişin kaybolduğunu, hassas olmayan tercihlerin kaldığını kontrol edin.
- Geçersiz AI anahtarı ve zaman aşımı durumlarını deneyin.
- Bekleyen yanıt sırasında uygulamayı durdurun; eski yanıtın ekranda görünmediğini kontrol edin.
- Sohbeti temizlemenin yalnızca yerel içeriği etkilediğini doğrulayın.
- TTS kullanılamadığında geri dönüş davranışını kontrol edin.

## Dosyalar

- `app/src/main/java/ai/tinkered/jarvis/MainActivity.kt` — Android uygulama ekranı ve etkileşimleri.
- `app/src/main/java/ai/tinkered/jarvis/CommandRouter.kt` — yerel komut yönlendirme mantığı.
- `app/src/test/java/ai/tinkered/jarvis/CommandRouterTest.kt` — JUnit 4 komut yönlendirici test kaynağı.
- `app/src/main/AndroidManifest.xml` — Android uygulama bildirimi.
- `app/build.gradle.kts` — uygulama modülünün Gradle yapılandırması.
- `build.gradle.kts` — kök Gradle yapılandırması.
- `settings.gradle.kts` — Gradle proje ayarları.
- `gradle.properties` — Gradle özellikleri.
- `design/spec.json` — proje tasarım belirtimi.
- `.gitignore` — Git tarafından yok sayılan dosyalar.