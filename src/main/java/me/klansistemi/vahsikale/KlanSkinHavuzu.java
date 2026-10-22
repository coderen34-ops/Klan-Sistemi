package me.klansistemi.vahsikale;

import java.util.Random;

/**
 * Vahşi Kale "insan" tipi klanların nöbetçi/lider görünümü için skin havuzu (PlayerDisguise ile
 * Mojang'tan gerçek skin çekiliyor). Amaç: her kale farklı bir klanmış hissi versin, tek tip
 * MHF_Zombie/MHF_Herobrine tekrarı olmasın. Vindicator tipi kalelerde disguise hiç kullanılmaz
 * (skinAdi null döner) - ham canavar görünümü bilerek korunuyor.
 */
final class KlanSkinHavuzu {

    private KlanSkinHavuzu() { }

    // Bilinen/klasik hesaplar (MHF_ "Minecraft Head Format" NPC başları + efsanevi geliştirici
    // isimleri) - hepsi gerçek skin'e sahip, uzun zamandır sunucu NPC'lerinde kullanılan klasikler.
    private static final String[] INSAN_HAVUZU = {
            "MHF_Herobrine", "MHF_Hero", "MHF_Hero2", "Notch", "jeb_", "Dinnerbone", "Grumm", "Marc_IRL",
            "MHF_Steve", "MHF_Alex", "MHF_Question", "MHF_Exploding"
    };

    // Lider genelde daha "efsanevi" isimlerden seçilsin (sıradan nöbetçilerle aynı çıkma ihtimali azalsın)
    private static final String[] LIDER_HAVUZU = {
            "MHF_Herobrine", "Notch", "jeb_", "Dinnerbone", "Grumm", "MHF_Hero2"
    };

    static String rastgeleKlanTuru(Random rng) {
        return rng.nextDouble() < 0.25 ? "VINDICATOR" : "INSAN";
    }

    /** Vindicator tipi klanlarda null döner (disguise uygulanmaz, ham görünüm kalır). */
    static String nobetciSkin(String klanTuru, Random rng) {
        if ("VINDICATOR".equals(klanTuru)) return null;
        return INSAN_HAVUZU[rng.nextInt(INSAN_HAVUZU.length)];
    }

    static String liderSkin(String klanTuru, Random rng) {
        if ("VINDICATOR".equals(klanTuru)) return null;
        return LIDER_HAVUZU[rng.nextInt(LIDER_HAVUZU.length)];
    }
}
