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

    static void giydir(LivingEntity mob, String skinAdi, String goruntuAdi) {
        if (DisguiseAPI.isDisguised(mob)) DisguiseAPI.undisguiseToAll(mob);
        PlayerDisguise disguise = new PlayerDisguise(skinAdi);
        disguise.setHideArmorFromSelf(false);
        if (goruntuAdi != null && !goruntuAdi.isEmpty()) {
            disguise.setName(goruntuAdi);
            disguise.setTablistName(goruntuAdi);
        }
        DisguiseAPI.disguiseToAll(mob, disguise);
    }

    static void soyun(LivingEntity mob) {
        if (DisguiseAPI.isDisguised(mob)) DisguiseAPI.undisguiseToAll(mob);
    }
}
