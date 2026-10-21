package me.klansistemi.vahsikale;

import java.util.List;
import java.util.Random;

/**
 * Vahşi Kale isimlerini seviyeye göre tonu değişen ön-ek + art-ek kombinasyonuyla üretir.
 * Örn. seviye 1: "Çakal Sürüsü" tarzı sıradan/zayıf; seviye 5: "Kara Diken Klanı" tarzı ağır/vahşi.
 * Üretilen isim çağıranda PERSIST edilir (VahsiKaleYonetici) - burada sadece üretim mantığı var,
 * sunucu her restart'ta yeniden çağrılmaz.
 */
final class IsimUretici {

    private IsimUretici() {}

    private static final Random RNG = new Random();

    // Seviye 1-2: sıradan/zayıf hissettiren isimler
    private static final List<String> ZAYIF_ONEK = List.of("Çakal", "Sırtlan", "Leş", "Pas", "Çamur", "Kemirgen");
    private static final List<String> ZAYIF_ARTEK = List.of("Sürüsü", "Çetesi", "Takımı", "Avanesi", "Güruhu");

    // Seviye 3: orta
    private static final List<String> ORTA_ONEK = List.of("Kurt", "Ejder", "Demir", "Kül", "Zift", "Boğa");
    private static final List<String> ORTA_ARTEK = List.of("Klanı", "Ordası", "Kabilesi", "Birliği");

    // Seviye 4-5: ağır/vahşi hissettiren isimler
    private static final List<String> AGIR_ONEK = List.of("Kara", "Kan", "Gölge", "Alev", "Buz", "Yıldırım", "Ecel");
    private static final List<String> AGIR_ARTEK = List.of("Diken Klanı", "Pençe Klanı", "Kafatası Klanı", "Boğazlayıcılar", "Cellat Ordası");

    static String uret(int seviye) {
        List<String> onekler;
        List<String> artekler;
        if (seviye <= 2) {
            onekler = ZAYIF_ONEK;
            artekler = ZAYIF_ARTEK;
        } else if (seviye == 3) {
            onekler = ORTA_ONEK;
            artekler = ORTA_ARTEK;
        } else {
            onekler = AGIR_ONEK;
            artekler = AGIR_ARTEK;
        }
        String onek = onekler.get(RNG.nextInt(onekler.size()));
        String artek = artekler.get(RNG.nextInt(artekler.size()));
        return onek + " " + artek;
    }
}
