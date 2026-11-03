package me.klansistemi.api;

import java.util.List;
import java.util.UUID;

/**
 * Koninski-Mobil köprüsü için Klan-Sistemi servisi. Bukkit ServicesManager ile alınır:
 * {@code Bukkit.getServicesManager().load(KlanAPI.class)}. Tüm çağrılar ANA THREAD'de yapılmalıdır.
 * Player gerektirmez; UUID ile çalışır (çevrimdışı oyuncu olabilir).
 *
 * NOT: Oyunda ayrı bir ittifak teklifi/kabul akışı YOKTUR. İttifak, iki klanın karşılıklı DOST görüşü
 * yazmasıyla kurulur; bu yüzden kabul/ret metodu yoktur. Görüş yazmak (gorusYaz) tek yoldur.
 */
public interface KlanAPI {

    default int apiSurum() { return 1; }

    List<KlanBilgisi> klanlar();

    /** Ada göre klan (büyük/küçük harf duyarsız); yoksa null. */
    KlanBilgisi klanBul(String ad);

    /** Oyuncunun klanı; klansızsa null. */
    KlanBilgisi oyuncununKlani(UUID oyuncu);

    /** Tüm görüşler (yazan klan -> hedef klan). */
    List<GorusBilgisi> tumGorusler();

    /** Bir klanın yazdığı görüşler. */
    List<GorusBilgisi> gorusler(UUID klanId);

    /** Bir klanın resmi ilişkileri (yalnız DOST/HUSUMET olanlar). */
    List<IliskiBilgisi> iliskiler(UUID klanId);

    /**
     * Oyuncu (klanının lideri olmalı) hedef klan hakkında görüş yazar/günceller. Oyundaki /klan iliski ile AYNI kurallar:
     * yalnızca lider, kendi klanına yazılamaz, değiştirme bekleme süresi, not uzunluğu, yasaklı kelime ve reklam filtresi,
     * dostluk bozulduktan sonra husumet bekleme süresi. durum null ise mevcut durum korunur (yalnız not değişir);
     * not null ise mevcut not korunur (yalnız durum değişir). durum: "DOST", "TARAFSIZ" veya "HUSUMET".
     * Notta kısıtlama: en fazla {@code iliski.not-max-karakter} karakter (varsayılan 100); '&' ve '§' temizlenir.
     * Aynı klana yeniden yazmak eski görüşün ÜZERİNE YAZAR; silme yoktur (TARAFSIZ + boş not en yakın karşılıktır).
     */
    GorusSonucu gorusYaz(UUID yazanOyuncu, String hedefKlan, String durum, String not);
}
