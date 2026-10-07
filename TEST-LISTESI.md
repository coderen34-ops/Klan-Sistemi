# Sunucu Test Listesi

Henüz sunucuda denenmemiş her şey. Denedikçe `[ ]` → `[x]` yap; sorun çıkarsa maddenin altına not düş.
Kurulum: `MeslekSistemi-1.0.jar`, `TagPlugin-1.0.jar`, `KlanSistemi-1.0.jar` (+ GriefPrevention3D; önerilen: LuckPerms, GSit, PlaceholderAPI).
Denemeden önce `plugins/` klasörünün yedeğini al.

## Hazırlık (bir kez, yetkili olarak)
- [ ] `/kasaayarla` ile Belediye kasası ayarlı (kira, maaş, borsa bunu kullanır)
- [ ] `/klan admin arena kur <isim>` + `pos1` `pos2` `spawn1` `spawn2` → `/klan admin arena liste` "Hazır" diyor (arena GP3D'de PvP açık yerde)
- [ ] `/klan admin guvenli merkez` köy merkezinde
- [ ] `/klan admin etiketyenile`

## Meslek-Sistemi
- [ ] Köylü stokları: bir ürünün 8 alım sınırı dolunca SADECE o ürün "Stok Tükendi", diğerleri satılıyor
- [ ] Meslek bloğu: hiç ticaret yapılmamış köylünün kürsüsünü kır → hemen işsiz kalıyor; tekrar koyunca meslek alıyor
- [ ] Ticaret yapılmış köylünün kürsüsünü kır → mesleği kalıyor (vanilla gibi)
- [ ] Emlak / Madenci / Oduncu NPC'lerinin kıyafeti (mesleği) değişmiyor
- [ ] Hastane NPC'si: sağlıklıyken ücret almıyor, yaralı/kanayan/kırıkken 350$ alıp tedavi ediyor
- [ ] Hapisteyken sadece izinli komutlar çalışıyor (`/essentials:home` gibi önekli yazımlar da engelli)
- [ ] Banka menüsünde miktar olarak `NaN` yazmak reddediliyor
- [ ] Sözleşme: iş bitince işveren çevrimdışıysa fatura girişte geliyor; başka yazılı kitaplar silinmiyor
- [ ] Mahkeme hapis cezası gerçekten hücreye atıyor (sanık çevrimdışıysa girişte)
- [ ] Kira: `/kiraver`, başvuru → `/kirasozlesme` → imza → kiracı kabul → `/kira` → kiradan çık

## Tag-Manager
- [ ] `/tag setek <oyuncu> &8[&6Test&8]` → chat, tab ve kafa üstünde `[Test] [Meslek] İsim [Sonek]`
- [ ] `/tag removeek <oyuncu>` → ek etiket kalkıyor, meslek etiketi bozulmuyor

## Klan — temel
- [ ] `/klan kur <isim>` (50.000 bankadan), küfürlü/boşluklu/aynı isim reddediliyor
- [ ] Davet [KATIL] butonu, ayrılınca 24 saat başka klana girememe
- [ ] Terfi/indir/kıdem; lider `/klan ayril` → 1. kıdemli yardımcı lider oluyor; yardımcı yoksa onay + dağılma
- [ ] Kasa: yatır/çek, yardımcı günlük limit, üye çekemiyor, log dosyası `plugins/KlanSistemi/loglar/`
- [ ] Aidat (varsayılan 1.000): ödeme, 24s/1s hatırlatma, ek süre, BORÇLU, lidere bildirim, 3. dönemde atılma
- [ ] `/klan sohbet` ve `/kc <mesaj>`; Meslek'in "chat'e yaz" adımlarıyla karışmıyor
- [ ] Klan etiketi otomatik geliyor/gidiyor (kur, katıl, ayrıl, atıl, dağıl)
- [ ] PlaceholderAPI kuruluysa: `%klan_isim%` `%klan_rol%` `%klan_uye%` `%klan_prestij%` `%klan_sira%`

## Klan — kasa, ilişkiler, panel
- [ ] Eşya kasası sayfaları; üye sadece koyabiliyor, yardımcı günlük 128 adet alabiliyor
- [ ] Klan dağılınca normal eşyalar `/klan ganimet` ile alınıyor
- [ ] `/klan panel` sadece lidere açık; DOST ikisi de seçince ittifak, HUSUMET tek taraflı; 1 saat / 12 saat beklemeler
- [ ] Müttefikler birbirine PvP hasarı veremiyor

## Klan — savaş
- [ ] Teklif (husumet şart) → kabul → kasalar kilitli → 60 sn hazırlık → `/klan savas katil`
- [ ] Arenada puan, 5 sn korumalı yeniden doğma, skor çubuğu, hedef puan / süre sonu
- [ ] Savaşta oyundan çıkıp 60 sn içinde dönme (geri ışınlanıyor) ve dönmeme (rakibe +1)
- [ ] Bitince herkes eski konumuna dönüyor; ganimet: para + normal eşya (+ büyülü eşya, aşağıda)
- [ ] Savaşta polis copu hapse atmıyor; savaşta hapis cezası alan savaş bitince hücreye gidiyor
- [ ] Açık dünya prestiji: husumetli klan üyesini ağır yaralayınca +1; güvenli bölgede (claim, köy merkezi) puan yok; `/klan prestij`

## Büyü uzmanlığı (dal: buyu-uzmanligi)
- [ ] `/klan uzmanlik liste`, `al KAZMA` (100.000 klan kasasından), başka klan aynı alanı alamıyor, `birak`
- [ ] `/klan atolye`: malzemeler eşya kasasında yokken kırmızı; varken üretim → eşya kasasına giriyor, lore'da "Büyü Kaynağı | ID"
- [ ] Atölye kotaları: klan günde 5, üye günde 2
- [ ] Üye kasadan klan eşyasını alabiliyor (en fazla 3); borçlu alamıyor
- [ ] Klan eşyası sandığa / shulker'a / ender sandığına / bundle'a / eşya çerçevesine / zırh askısına konamıyor, yere atılamıyor
- [ ] Örs: isim verme ve malzemeyle tamir çalışıyor, büyüler (Efficiency 7 vb.) korunuyor; büyü kitabıyla birleştirme engelli
- [ ] Taş çarkı, büyü masası, crafting tamiri engelli; demircide netherit yükseltmesi çalışıyor ve kimlik korunuyor
- [ ] Klan eşyasıyla öl → eşya kasaya dönüyor
- [ ] Klan eşyası üzerindeyken klandan ayrıl / atıl → kasaya dönüyor (çevrimdışıysa girişte)
- [ ] Eşya kırılınca `/klan admin buyu defter <kod>` → SILINDI
- [ ] Savaşta üyeler klan eşyalarını kullanabiliyor; arenada büyüler vanilla seviyesinde (örn. Efficiency 7 → 5), savaştan çıkınca eski seviye geri geliyor
- [ ] Savaşı kazanınca kaybedenin büyülü eşyalarının bir kısmı kazananın kasasına geçiyor, lore'da "Ele Geçirildi"
- [ ] Kaybeden tarafta ödünçteki büyülü eşya: çevrimiçiyse anında alınıyor, çevrimdışıysa girişte kayboluyor
- [ ] Arenada ganimet olarak alınan eşya kazananın kasasına asıl seviyesiyle (düşürülmemiş) giriyor
- [ ] Savaş sırasında sunucu kapanırsa / oyuncu çıkarsa girişte büyü seviyeleri geri yükleniyor

### Büyü Ustası (ticari katman)
- [ ] config'te `buyu.ticari.disariya-hizmet: true` yap → `/klan admin yenile`; kapalıyken `/klan buyu` "hizmet vermiyor" diyor
- [ ] Lider: `/klan buyu fiyat efficiency 20000` (5.000-100.000 dışı reddediliyor), `/klan buyu fiyatlar`, `fiyat <büyü> sil`
- [ ] `/klan buyu` ve `/klan admin buyu npc kur|sil` ile Büyü Ustası NPC'si: klan listesinde sadece fiyat koymuş, savaşta olmayan klanlar
- [ ] Başka klandan oyuncu elinde kazmayla büyü alıyor: bankadan para düşüyor, klan kasasına %90 giriyor, Efficiency 5 basılıyor
- [ ] Lore: "Büyü Kaynağı: <Klan> | Ticari | ID: ..."; eşya sandığa konabiliyor, takas edilebiliyor, yere atılabiliyor
- [ ] Aynı eşyaya aynı klandan ikinci büyü (fortune) eklenebiliyor, ID değişmiyor; başka klan eklemiyor; klan eşyasına satış yok
- [ ] Çakışan büyü (örn. Silk Touch varken Fortune) ve zaten olan seviye reddediliyor
- [ ] Kotalar: klan günde 10 satış, bir alıcı aynı klandan günde 2
- [ ] Klan üyesi kendi klanından alınca ya da aynı IP'den alımda adminlere uyarı + log
- [ ] Diplomasi Paneli → "Büyü Satışları" sekmesi ve `/klan buyu gecmis`
- [ ] Ticari eşya örs/taş çarkı/büyü masasında birleştirilemiyor; arenada vanilla seviyesine iniyor
- [ ] Ticari eşya kopyalanınca (aynı oyuncuda iki tane) fazlası siliniyor; kırılınca defterde SILINDI
