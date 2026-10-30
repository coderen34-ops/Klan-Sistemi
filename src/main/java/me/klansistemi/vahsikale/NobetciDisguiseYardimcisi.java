package me.klansistemi.vahsikale;

import org.bukkit.entity.LivingEntity;

import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;

/**
 * LibsDisguises'e özel kod, sınıf-yükleme sırasında me.libraryaddict.* sınıflarını çözemediği için
 * VahsiKaleYonetici'yi çökertmesin diye bilerek ayrı bir sınıfa alındı (Meslek-Sistemi'ndeki
 * StandDisguiseYardimcisi ile aynı yöntem). Sadece çağrıldığında ve LibsDisguises etkinken yüklenir.
 *
 * Nöbetçi/lider NPC'leri gerçek bir hostile mob (Vindicator) üzerine kurulur - savaş AI'ı (pathfinding,
 * menzil tutma, saldırı) bedavaya gelir - ve insan gibi görünmesi için PlayerDisguise giydirilir.
 * Disguise'ın skin'i bilinen/gerçek bir oyuncu adına bağlı DEĞİL (bu bir NPC, kiralayan oyuncu yok);
 * isim bazlı Mojang çözümlemesi offline-mode'da/ağ sorununda başarısız olup Steve'de kalabilir - bu,
 * sadece kozmetik bir risktir, savaş davranışını etkilemez.
 */
final class NobetciDisguiseYardimcisi {

    private NobetciDisguiseYardimcisi() {}

    /** @return disguise gerçekten tutmuş mu (DisguiseAPI.isDisguised ile hemen sonra doğrulanır) - çağıran taraf
     *  false dönerse loglayıp görünür hale getirebilir (örn. ağ/skin çözümleme hatası sessizce yutulmasın). */
    static boolean giydir(LivingEntity mob, String skinAdi, String goruntuAdi) {
        if (DisguiseAPI.isDisguised(mob)) DisguiseAPI.undisguiseToAll(mob);
        PlayerDisguise disguise = new PlayerDisguise(skinAdi);
        disguise.setHideArmorFromSelf(false);
        if (goruntuAdi != null && !goruntuAdi.isEmpty()) {
            disguise.setName(goruntuAdi);
            disguise.setTablistName(goruntuAdi);
            disguise.setNameVisible(false); // isim sadece nişan alınınca Meslek arayüzünde gösterilir
        }
        DisguiseAPI.disguiseToAll(mob, disguise);
        boolean ok = DisguiseAPI.isDisguised(mob);
        if (ok) mob.setSilent(true); // taban mobun (domuz-zombi/vindicator) sesi çıkmasın; oyuncu sesleri VahsiKaleYonetici'de çalınır
        return ok;
    }

    static void soyun(LivingEntity mob) {
        if (DisguiseAPI.isDisguised(mob)) DisguiseAPI.undisguiseToAll(mob);
        mob.setSilent(false);
    }

    static boolean disguisliMi(LivingEntity mob) {
        return DisguiseAPI.isDisguised(mob);
    }
}
